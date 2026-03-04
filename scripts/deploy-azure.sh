#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy-azure.sh — Despliegue completo en Azure
#
# Crea y configura todos los recursos necesarios para ejecutar el DID Issuer
# en Azure Container Apps (serverless, escala a 0 réplicas cuando no hay tráfico).
#
# Recursos creados:
#   • Resource Group
#   • Azure Container Registry (ACR)          — almacena la imagen Docker
#   • Azure Container Apps Environment        — plataforma serverless
#   • Azure Container App                     — el backend Java
#   • Azure Database for PostgreSQL           — base de datos en producción
#   • Azure Key Vault                         — clave privada del emisor + password BD
#   • User-Assigned Managed Identity          — acceso sin contraseña a Key Vault
#
# Uso:
#   ./scripts/deploy-azure.sh                     # primer despliegue
#   ./scripts/deploy-azure.sh                     # redeploy (reutiliza recursos existentes)
#   RESOURCE_GROUP=mi-rg ./scripts/deploy-azure.sh
#
# Requisitos:
#   • Azure CLI (az) autenticado: az login
#   • openssl
#   • python3
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ─── Configuración (sobreescribir con env vars si es necesario) ───────────────
RESOURCE_GROUP="${RESOURCE_GROUP:-did-issuer-rg}"
LOCATION="${LOCATION:-eastus}"

# Sufijo único para nombres de recursos globalmente únicos (ACR, Key Vault, PostgreSQL).
# Se persiste en .azure-suffix para reutilizar en redespliegues.
SUFFIX_FILE="$PROJECT_ROOT/.azure-suffix"
if [ -f "$SUFFIX_FILE" ]; then
  SUFFIX=$(cat "$SUFFIX_FILE")
else
  SUFFIX=$(python3 -c "import uuid; print(uuid.uuid4().hex[:6])")
  echo "$SUFFIX" > "$SUFFIX_FILE"
fi

ACR_NAME="didissueracr${SUFFIX}"
KV_NAME="did-issuer-kv-${SUFFIX}"
PG_SERVER="did-issuer-pg-${SUFFIX}"
ACA_ENV="did-issuer-env"
ACA_NAME="did-issuer-app"
IDENTITY_NAME="did-issuer-id"
IMAGE_TAG="did-issuer:latest"

# ─── Colores ──────────────────────────────────────────────────────────────────
ok()    { echo -e "      \033[32m✓\033[0m $*"; }
skip()  { echo -e "      \033[33m↷\033[0m $* (ya existe, reutilizando)"; }
step()  { echo -e "\033[34m$*\033[0m"; }
die()   { echo -e "\033[31mERROR: $*\033[0m" >&2; exit 1; }

# Verifica si un recurso existe usando un comando az show.
# Uso: exists "az acr show -g rg -n name"
exists() { eval "$*" >/dev/null 2>&1; }

# ─── Prerequisitos ────────────────────────────────────────────────────────────
command -v az      >/dev/null || die "Azure CLI no encontrado. Instalar: https://aka.ms/install-azure-cli"
command -v openssl >/dev/null || die "openssl no encontrado"
command -v python3 >/dev/null || die "python3 no encontrado"

az account show >/dev/null 2>&1 || die "No hay sesión activa. Ejecuta: az login"

SUBSCRIPTION=$(az account show --query name -o tsv)
SUBSCRIPTION_ID=$(az account show --query id -o tsv)

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  DID Issuer — Despliegue en Azure"
echo "  Resource Group : $RESOURCE_GROUP"
echo "  Ubicación      : $LOCATION"
echo "  Sufijo         : $SUFFIX"
echo "  Suscripción    : $SUBSCRIPTION"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# ─── [1/12] Resource Group ────────────────────────────────────────────────────
step "[1/12] Resource Group"
az group create -n "$RESOURCE_GROUP" -l "$LOCATION" --output none
ok "Resource Group: $RESOURCE_GROUP"

# ─── [2/12] Azure Container Registry ─────────────────────────────────────────
step "[2/12] Azure Container Registry"
if exists "az acr show -g '$RESOURCE_GROUP' -n '$ACR_NAME'"; then
  skip "ACR $ACR_NAME"
else
  az acr create -g "$RESOURCE_GROUP" -n "$ACR_NAME" \
    --sku Basic --admin-enabled true --output none
fi
ACR_SERVER=$(az acr show -g "$RESOURCE_GROUP" -n "$ACR_NAME" --query loginServer -o tsv)
ok "ACR: $ACR_SERVER"

# ─── [3/12] Build y Push de la imagen ────────────────────────────────────────
step "[3/12] Build imagen Docker (Azure ACR Build — no requiere Docker local)"
az acr build \
  -g "$RESOURCE_GROUP" \
  -r "$ACR_NAME" \
  -t "$IMAGE_TAG" \
  -f "$PROJECT_ROOT/docker/Dockerfile" \
  "$PROJECT_ROOT/backend" \
  --output none
ok "Imagen lista: $ACR_SERVER/$IMAGE_TAG"

# ─── [4/12] Azure Key Vault ───────────────────────────────────────────────────
step "[4/12] Azure Key Vault"
if exists "az keyvault show -g '$RESOURCE_GROUP' -n '$KV_NAME'"; then
  skip "Key Vault $KV_NAME"
else
  az keyvault create \
    -g "$RESOURCE_GROUP" \
    -n "$KV_NAME" \
    -l "$LOCATION" \
    --enable-rbac-authorization true \
    --output none
fi
KV_RESOURCE_ID=$(az keyvault show -g "$RESOURCE_GROUP" -n "$KV_NAME" --query id -o tsv)
KV_URI="https://${KV_NAME}.vault.azure.net"
ok "Key Vault: $KV_NAME"

# Asignar Key Vault Secrets Officer al usuario/SP que ejecuta el script,
# para que pueda escribir secrets durante el despliegue.
# (Distinto del rol que se le da a la Managed Identity del contenedor en paso 8)
CALLER_OID=$(az ad signed-in-user show --query id -o tsv 2>/dev/null || echo "")
if [ -z "$CALLER_OID" ]; then
  # Ejecutando como Service Principal (CI/CD)
  SP_APP_ID=$(az account show --query user.name -o tsv 2>/dev/null || echo "")
  CALLER_OID=$(az ad sp show --id "$SP_APP_ID" --query id -o tsv 2>/dev/null || echo "")
fi

if [ -n "$CALLER_OID" ]; then
  CALLER_ROLE_COUNT=$(az role assignment list \
    --assignee "$CALLER_OID" \
    --scope "$KV_RESOURCE_ID" \
    --query "[?roleDefinitionName=='Key Vault Secrets Officer'] | length(@)" \
    -o tsv 2>/dev/null || echo "0")

  if [ "${CALLER_ROLE_COUNT:-0}" = "0" ]; then
    az role assignment create \
      --role "Key Vault Secrets Officer" \
      --assignee-object-id "$CALLER_OID" \
      --scope "$KV_RESOURCE_ID" \
      --assignee-principal-type User \
      --output none 2>/dev/null || \
    az role assignment create \
      --role "Key Vault Secrets Officer" \
      --assignee-object-id "$CALLER_OID" \
      --scope "$KV_RESOURCE_ID" \
      --assignee-principal-type ServicePrincipal \
      --output none
    ok "Key Vault Secrets Officer asignado al usuario actual"
    echo "      Esperando propagación de permisos (20s)..."
    sleep 20
  else
    skip "rol Key Vault Secrets Officer (usuario actual)"
  fi
else
  echo "      ⚠ No se pudo obtener el OID del caller — asigna manualmente Key Vault Secrets Officer"
fi

# ─── [5/12] Clave secp256k1 del emisor → Key Vault ───────────────────────────
step "[5/12] Clave privada del emisor"
SECRET_COUNT=$(az keyvault secret list \
  --vault-name "$KV_NAME" \
  --query "[?name=='issuer-key-pem'] | length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "${SECRET_COUNT:-0}" = "0" ]; then
  TMP_KEY=$(mktemp)
  openssl ecparam -name secp256k1 -genkey -noout 2>/dev/null \
    | openssl ec -out "$TMP_KEY" 2>/dev/null
  KEY_B64=$(base64 < "$TMP_KEY" | tr -d '\n')
  rm -f "$TMP_KEY"
  az keyvault secret set \
    --vault-name "$KV_NAME" \
    --name issuer-key-pem \
    --value "$KEY_B64" \
    --output none
  ok "Clave generada y almacenada en Key Vault (secret: issuer-key-pem)"
else
  skip "secret issuer-key-pem"
fi

# ─── [6/12] PostgreSQL Flexible Server ───────────────────────────────────────
step "[6/12] Azure Database for PostgreSQL Flexible Server"
PG_PASS_COUNT=$(az keyvault secret list \
  --vault-name "$KV_NAME" \
  --query "[?name=='pg-password'] | length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "${PG_PASS_COUNT:-0}" = "0" ]; then
  PG_PASSWORD=$(python3 -c \
    "import secrets,string; c=string.ascii_letters+string.digits+'!@#%'; \
     print(''.join(secrets.choice(c) for _ in range(24)))")
  az keyvault secret set \
    --vault-name "$KV_NAME" \
    --name pg-password \
    --value "$PG_PASSWORD" \
    --output none
else
  PG_PASSWORD=$(az keyvault secret show \
    --vault-name "$KV_NAME" --name pg-password --query value -o tsv)
fi

if exists "az postgres flexible-server show -g '$RESOURCE_GROUP' -n '$PG_SERVER'"; then
  skip "PostgreSQL $PG_SERVER"
else
  az postgres flexible-server create \
    -g "$RESOURCE_GROUP" \
    -n "$PG_SERVER" \
    -l "$LOCATION" \
    --sku-name Standard_B1ms \
    --tier Burstable \
    --admin-user didadmin \
    --admin-password "$PG_PASSWORD" \
    --database-name did_issuer \
    --public-access 0.0.0.0 \
    --yes \
    --output none
fi

PG_HOST=$(az postgres flexible-server show \
  -g "$RESOURCE_GROUP" -n "$PG_SERVER" \
  --query fullyQualifiedDomainName -o tsv)
JDBC_URL="jdbc:postgresql://${PG_HOST}:5432/did_issuer?sslmode=require"
ok "PostgreSQL: $PG_HOST"

# ─── [7/12] Managed Identity ─────────────────────────────────────────────────
step "[7/12] User-Assigned Managed Identity"
if exists "az identity show -g '$RESOURCE_GROUP' -n '$IDENTITY_NAME'"; then
  skip "Identity $IDENTITY_NAME"
else
  az identity create \
    -g "$RESOURCE_GROUP" -n "$IDENTITY_NAME" -l "$LOCATION" \
    --output none
fi
IDENTITY_RESOURCE_ID=$(az identity show \
  -g "$RESOURCE_GROUP" -n "$IDENTITY_NAME" --query id -o tsv)
IDENTITY_PRINCIPAL_ID=$(az identity show \
  -g "$RESOURCE_GROUP" -n "$IDENTITY_NAME" --query principalId -o tsv)
ok "Identity: $IDENTITY_NAME"

# ─── [8/12] RBAC: Identity → Key Vault ───────────────────────────────────────
step "[8/12] Permisos Key Vault (Key Vault Secrets User)"
ROLE_COUNT=$(az role assignment list \
  --assignee "$IDENTITY_PRINCIPAL_ID" \
  --scope "$KV_RESOURCE_ID" \
  --query "[?roleDefinitionName=='Key Vault Secrets User'] | length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "${ROLE_COUNT:-0}" = "0" ]; then
  az role assignment create \
    --role "Key Vault Secrets User" \
    --assignee-object-id "$IDENTITY_PRINCIPAL_ID" \
    --scope "$KV_RESOURCE_ID" \
    --assignee-principal-type ServicePrincipal \
    --output none
  ok "Key Vault Secrets User asignado"
else
  skip "rol Key Vault Secrets User"
fi

# ─── [9/12] Container Apps Environment ───────────────────────────────────────
step "[9/12] Azure Container Apps Environment"
if exists "az containerapp env show -g '$RESOURCE_GROUP' -n '$ACA_ENV'"; then
  skip "Environment $ACA_ENV"
else
  az containerapp env create \
    -g "$RESOURCE_GROUP" \
    -n "$ACA_ENV" \
    -l "$LOCATION" \
    --output none
fi
ok "Environment: $ACA_ENV"

echo "      Esperando propagación de permisos RBAC (30s)..."
sleep 30

# ─── [10/12] Container App ───────────────────────────────────────────────────
step "[10/12] Azure Container App"
ACR_PASSWORD=$(az acr credential show \
  -g "$RESOURCE_GROUP" -n "$ACR_NAME" \
  --query "passwords[0].value" -o tsv)

if exists "az containerapp show -g '$RESOURCE_GROUP' -n '$ACA_NAME'"; then
  echo "      Actualizando imagen del Container App existente..."
  az containerapp update \
    -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
    --image "${ACR_SERVER}/${IMAGE_TAG}" \
    --output none
else
  az containerapp create \
    -g "$RESOURCE_GROUP" \
    -n "$ACA_NAME" \
    --environment "$ACA_ENV" \
    --image "${ACR_SERVER}/${IMAGE_TAG}" \
    --registry-server "$ACR_SERVER" \
    --registry-username "$ACR_NAME" \
    --registry-password "$ACR_PASSWORD" \
    --user-assigned "$IDENTITY_RESOURCE_ID" \
    --min-replicas 0 \
    --max-replicas 3 \
    --cpu 0.5 \
    --memory 1Gi \
    --target-port 8080 \
    --ingress external \
    --secrets \
      "issuer-key=keyvaultref:${KV_URI}/secrets/issuer-key-pem,identityref:${IDENTITY_RESOURCE_ID}" \
      "pg-password=keyvaultref:${KV_URI}/secrets/pg-password,identityref:${IDENTITY_RESOURCE_ID}" \
    --env-vars \
      "ISSUER_KEY_PEM=secretref:issuer-key" \
      "SPRING_DATASOURCE_URL=${JDBC_URL}" \
      "SPRING_DATASOURCE_USERNAME=didadmin" \
      "SPRING_DATASOURCE_PASSWORD=secretref:pg-password" \
      "SPRING_DATASOURCE_DRIVER=org.postgresql.Driver" \
      "JPA_DIALECT=org.hibernate.dialect.PostgreSQLDialect" \
      "H2_CONSOLE_ENABLED=false" \
      "ISSUER_BASE_URL=https://pending" \
    --output none
fi
ok "Container App: $ACA_NAME"

# ─── [11/12] Actualizar URL del emisor ────────────────────────────────────────
step "[11/12] Configurando URL pública del emisor"
APP_FQDN=$(az containerapp show \
  -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
  --query "properties.configuration.ingress.fqdn" -o tsv)
APP_URL="https://${APP_FQDN}"

az containerapp update \
  -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
  --set-env-vars "ISSUER_BASE_URL=${APP_URL}" \
  --output none
ok "URL del emisor: $APP_URL"

# ─── [12/12] Guardar outputs ──────────────────────────────────────────────────
step "[12/12] Guardando outputs del despliegue"
OUTPUT_FILE="$PROJECT_ROOT/.azure-deploy-output.env"
cat > "$OUTPUT_FILE" <<EOF
# DID Issuer — Outputs del despliegue en Azure
# Generado: $(date "+%Y-%m-%d %H:%M:%S")

RESOURCE_GROUP=$RESOURCE_GROUP
LOCATION=$LOCATION
SUFFIX=$SUFFIX
SUBSCRIPTION_ID=$SUBSCRIPTION_ID

ACR_NAME=$ACR_NAME
ACR_SERVER=$ACR_SERVER
KV_NAME=$KV_NAME
PG_SERVER=$PG_SERVER
PG_HOST=$PG_HOST
JDBC_URL=$JDBC_URL
ACA_ENV=$ACA_ENV
ACA_NAME=$ACA_NAME
APP_URL=$APP_URL
EOF
ok "Outputs en: $OUTPUT_FILE"

# ─── Obtener DID del emisor (esperar cold start Java) ─────────────────────────
step "Obteniendo DID del emisor..."
ISSUER_DID=""
ISSUER_PUB_HEX=""
for i in $(seq 1 12); do
  DID_RESP=$(curl -sf --max-time 15 "${APP_URL}/issuer/did" 2>/dev/null || echo "")
  if [ -n "$DID_RESP" ]; then
    ISSUER_DID=$(echo "$DID_RESP" | python3 -c \
      "import sys,json; d=json.load(sys.stdin); print(d.get('did',''))" 2>/dev/null || echo "")
    ISSUER_PUB_HEX=$(echo "$DID_RESP" | python3 -c \
      "import sys,json; d=json.load(sys.stdin); print(d.get('public_key_hex',''))" 2>/dev/null || echo "")
    [ -n "$ISSUER_DID" ] && break
  fi
  echo "      Esperando que el backend inicie... ($i/12)"
  sleep 10
done

{
  echo ""
  echo "ISSUER_DID=$ISSUER_DID"
  echo "ISSUER_PUB_HEX=$ISSUER_PUB_HEX"
} >> "$OUTPUT_FILE"

# ─── mobile-config.txt ────────────────────────────────────────────────────────
MOBILE_CONFIG_FILE="$PROJECT_ROOT/mobile-config.txt"
cat > "$MOBILE_CONFIG_FILE" <<EOF
# ─── Configuración del Issuer para la app Android ────────────────────────────
# Generado: $(date "+%Y-%m-%d %H:%M:%S")

ISSUER_BASE_URL=$APP_URL

# Endpoints
GET  $APP_URL/credentials/nonce            → nonce de un solo uso (paso 1)
POST $APP_URL/credentials/issue            → emitir VC (paso 2, body: {holder_did, proof})
GET  $APP_URL/credentials?holder_did={did} → metadatos de VCs del holder
GET  $APP_URL/issuer/did                   → DID e info del emisor
GET  $APP_URL/issuer/did-document          → DID Document W3C del emisor

# Identidad del emisor
ISSUER_DID=$ISSUER_DID
ISSUER_PUBLIC_KEY_HEX=$ISSUER_PUB_HEX

# Configurar en MainActivity.java:
#   private static final String ISSUER_URL = "$APP_URL";
EOF

# ─── Resumen final ────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  DESPLIEGUE COMPLETADO"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  ┌─────────────────────────────────────────────────────────┐"
echo "  │  ENDPOINTS PARA EL EQUIPO MOBILE                        │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │                                                         │"
echo "  │  Base URL:                                              │"
printf "  │  %-55s│\n" "$APP_URL"
echo "  │                                                         │"
echo "  │  GET  /credentials/nonce         (paso 1, pedir VC)    │"
echo "  │  POST /credentials/issue         (paso 2, emitir VC)   │"
echo "  │  GET  /credentials?holder_did=   (metadatos)           │"
echo "  │  GET  /issuer/did                (info del emisor)     │"
echo "  │  GET  /issuer/did-document       (DID Document W3C)    │"
echo "  │                                                         │"
if [ -n "$ISSUER_DID" ]; then
echo "  │  DID del emisor:                                        │"
printf "  │  %-55s│\n" "$ISSUER_DID"
echo "  │                                                         │"
fi
echo "  └─────────────────────────────────────────────────────────┘"
echo ""
echo "  Archivo generado para el equipo mobile: mobile-config.txt"
echo ""
echo "  Verificar backend:"
echo "    curl $APP_URL/issuer/did | jq ."
echo ""
echo "  Pruebas de integración:"
echo "    ./scripts/test-integration.sh $APP_URL"
echo ""
echo "  Ver logs:"
echo "    az containerapp logs show -g $RESOURCE_GROUP -n $ACA_NAME --follow"
echo ""
echo "  Redesplegar código:"
echo "    ./scripts/deploy-azure.sh"
echo ""
echo "  Destruir recursos:"
echo "    az group delete -n $RESOURCE_GROUP --yes --no-wait"
echo "═══════════════════════════════════════════════════════════════"
