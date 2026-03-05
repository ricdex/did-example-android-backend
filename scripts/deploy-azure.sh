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
#   • Azure Storage Account + tabla           — persistencia de metadatos VC (Table Storage)
#   • Azure Key Vault                         — clave privada del emisor
#   • User-Assigned Managed Identity          — acceso sin contraseña a Key Vault y Table Storage
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
LOCATION="${LOCATION:-eastus2}"

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
STORAGE_ACCOUNT="didissuer${SUFFIX}"
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
echo "  Resource Group  : $RESOURCE_GROUP"
echo "  Ubicación       : $LOCATION"
echo "  Sufijo          : $SUFFIX"
echo "  Suscripción     : $SUBSCRIPTION"
echo "  Storage Account : $STORAGE_ACCOUNT"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# ─── [1/11] Resource Group ────────────────────────────────────────────────────
step "[1/11] Resource Group"
# Wait if the RG is being deleted from a previous run
for i in $(seq 1 24); do
  RG_STATE=$(az group show -n "$RESOURCE_GROUP" --query properties.provisioningState -o tsv 2>/dev/null || echo "Deleted")
  [ "$RG_STATE" != "Deleting" ] && break
  echo "      Esperando que se elimine el Resource Group anterior... ($i/24)"
  sleep 10
done
az group create -n "$RESOURCE_GROUP" -l "$LOCATION" --output none
ok "Resource Group: $RESOURCE_GROUP"

# ─── [2/11] Azure Container Registry ─────────────────────────────────────────
step "[2/11] Azure Container Registry"
if exists "az acr show -g '$RESOURCE_GROUP' -n '$ACR_NAME'"; then
  skip "ACR $ACR_NAME"
else
  az acr create -g "$RESOURCE_GROUP" -n "$ACR_NAME" \
    --sku Basic --admin-enabled true --output none
fi
ACR_SERVER=$(az acr show -g "$RESOURCE_GROUP" -n "$ACR_NAME" --query loginServer -o tsv)
ok "ACR: $ACR_SERVER"

# ─── [3/11] Build y Push de la imagen ────────────────────────────────────────
step "[3/11] Build imagen Docker (Azure ACR Build — no requiere Docker local)"
az acr build \
  -g "$RESOURCE_GROUP" \
  -r "$ACR_NAME" \
  -t "$IMAGE_TAG" \
  -f "$PROJECT_ROOT/docker/Dockerfile" \
  "$PROJECT_ROOT/backend" \
  --output none
ok "Imagen lista: $ACR_SERVER/$IMAGE_TAG"

# ─── [4/11] Azure Key Vault ───────────────────────────────────────────────────
step "[4/11] Azure Key Vault"
if exists "az keyvault show -g '$RESOURCE_GROUP' -n '$KV_NAME'"; then
  skip "Key Vault $KV_NAME"
else
  # Purge if soft-deleted so the name can be reused
  DELETED_KV_LOC=$(az keyvault list-deleted \
    --query "[?name=='$KV_NAME'].properties.location" -o tsv 2>/dev/null || echo "")
  if [ -n "$DELETED_KV_LOC" ]; then
    echo "      Purgando Key Vault soft-deleted: $KV_NAME (ubicación: $DELETED_KV_LOC)..."
    az keyvault purge -n "$KV_NAME" -l "$DELETED_KV_LOC" --output none
  fi
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

# ─── [5/11] Clave secp256k1 del emisor → Key Vault ───────────────────────────
step "[5/11] Clave privada del emisor"
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

# ─── [6/11] Azure Storage Account + tabla credentials ────────────────────────
step "[6/11] Azure Storage Account"
if exists "az storage account show -g '$RESOURCE_GROUP' -n '$STORAGE_ACCOUNT'"; then
  skip "Storage Account $STORAGE_ACCOUNT"
else
  az storage account create \
    -g "$RESOURCE_GROUP" \
    -n "$STORAGE_ACCOUNT" \
    -l "$LOCATION" \
    --sku Standard_LRS \
    --kind StorageV2 \
    --output none
fi
STORAGE_RESOURCE_ID=$(az storage account show \
  -g "$RESOURCE_GROUP" -n "$STORAGE_ACCOUNT" --query id -o tsv)
ok "Storage Account: $STORAGE_ACCOUNT"

# Almacenar la connection string del Storage Account en Key Vault
STORAGE_KEY_SECRET_COUNT=$(az keyvault secret list \
  --vault-name "$KV_NAME" \
  --query "[?name=='storage-connection-string'] | length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "${STORAGE_KEY_SECRET_COUNT:-0}" = "0" ]; then
  STORAGE_KEY=$(az storage account keys list \
    -g "$RESOURCE_GROUP" -n "$STORAGE_ACCOUNT" \
    --query "[0].value" -o tsv)
  STORAGE_CONN_STR="DefaultEndpointsProtocol=https;AccountName=${STORAGE_ACCOUNT};AccountKey=${STORAGE_KEY};EndpointSuffix=core.windows.net"
  az keyvault secret set \
    --vault-name "$KV_NAME" \
    --name storage-connection-string \
    --value "$STORAGE_CONN_STR" \
    --output none
  ok "Storage connection string almacenada en Key Vault (secret: storage-connection-string)"
else
  skip "secret storage-connection-string"
fi

# Crear tabla credentials (idempotente con --auth-mode login)
TABLE_EXISTS=$(az storage table exists \
  --account-name "$STORAGE_ACCOUNT" \
  --name credentials \
  --auth-mode login \
  --query exists -o tsv 2>/dev/null || echo "false")
if [ "$TABLE_EXISTS" != "true" ]; then
  az storage table create \
    --account-name "$STORAGE_ACCOUNT" \
    --name credentials \
    --auth-mode login \
    --output none
  ok "Tabla 'credentials' creada"
else
  skip "tabla credentials"
fi

# ─── [7/11] Managed Identity ─────────────────────────────────────────────────
step "[7/11] User-Assigned Managed Identity"
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
IDENTITY_CLIENT_ID=$(az identity show \
  -g "$RESOURCE_GROUP" -n "$IDENTITY_NAME" --query clientId -o tsv)
ok "Identity: $IDENTITY_NAME ($IDENTITY_CLIENT_ID)"

# ─── [8/11] RBAC: Identity → Key Vault + Storage ─────────────────────────────
step "[8/11] Permisos RBAC (Key Vault Secrets User + Storage Table Data Contributor)"
KV_ROLE_COUNT=$(az role assignment list \
  --assignee "$IDENTITY_PRINCIPAL_ID" \
  --scope "$KV_RESOURCE_ID" \
  --query "[?roleDefinitionName=='Key Vault Secrets User'] | length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "${KV_ROLE_COUNT:-0}" = "0" ]; then
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

STORAGE_ROLE_COUNT=$(az role assignment list \
  --assignee "$IDENTITY_PRINCIPAL_ID" \
  --scope "$STORAGE_RESOURCE_ID" \
  --query "[?roleDefinitionName=='Storage Table Data Contributor'] | length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "${STORAGE_ROLE_COUNT:-0}" = "0" ]; then
  az role assignment create \
    --role "Storage Table Data Contributor" \
    --assignee-object-id "$IDENTITY_PRINCIPAL_ID" \
    --scope "$STORAGE_RESOURCE_ID" \
    --assignee-principal-type ServicePrincipal \
    --output none
  ok "Storage Table Data Contributor asignado"
else
  skip "rol Storage Table Data Contributor"
fi

# ─── [9/11] Container Apps Environment ───────────────────────────────────────
step "[9/11] Azure Container Apps Environment"
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

# ─── [10/11] Container App ───────────────────────────────────────────────────
step "[10/11] Azure Container App"
ACR_PASSWORD=$(az acr credential show \
  -g "$RESOURCE_GROUP" -n "$ACR_NAME" \
  --query "passwords[0].value" -o tsv)

if exists "az containerapp show -g '$RESOURCE_GROUP' -n '$ACA_NAME'"; then
  echo "      Actualizando imagen y env vars del Container App existente..."
  # Eliminar env vars de PostgreSQL si quedaron de un deploy anterior
  PG_VARS=$(az containerapp show -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
    --query "properties.template.containers[0].env[?starts_with(name, 'SPRING_DATASOURCE') || name=='JPA_DIALECT'].name" \
    -o tsv 2>/dev/null || echo "")
  if [ -n "$PG_VARS" ]; then
    echo "      Eliminando env vars de PostgreSQL residuales: $PG_VARS"
    # shellcheck disable=SC2086
    az containerapp update -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
      --remove-env-vars $PG_VARS --output none 2>/dev/null || true
  fi
  az containerapp update \
    -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
    --image "${ACR_SERVER}/${IMAGE_TAG}" \
    --set-env-vars \
      "AZURE_STORAGE_ACCOUNT_NAME=${STORAGE_ACCOUNT}" \
      "H2_CONSOLE_ENABLED=false" \
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
      "storage-conn=keyvaultref:${KV_URI}/secrets/storage-connection-string,identityref:${IDENTITY_RESOURCE_ID}" \
    --env-vars \
      "ISSUER_KEY_PEM=secretref:issuer-key" \
      "AZURE_STORAGE_ACCOUNT_NAME=${STORAGE_ACCOUNT}" \
      "AZURE_STORAGE_CONNECTION_STRING=secretref:storage-conn" \
      "H2_CONSOLE_ENABLED=false" \
      "ISSUER_BASE_URL=https://pending" \
    --output none
fi
ok "Container App: $ACA_NAME"

# ─── [11/11] Actualizar URL del emisor ────────────────────────────────────────
step "[11/11] Configurando URL pública del emisor"
APP_FQDN=$(az containerapp show \
  -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
  --query "properties.configuration.ingress.fqdn" -o tsv)
APP_URL="https://${APP_FQDN}"

az containerapp update \
  -g "$RESOURCE_GROUP" -n "$ACA_NAME" \
  --set-env-vars "ISSUER_BASE_URL=${APP_URL}" \
  --output none
ok "URL del emisor: $APP_URL"

# Guardar outputs del despliegue
step "Guardando outputs del despliegue"
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
STORAGE_ACCOUNT=$STORAGE_ACCOUNT
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
GET  $APP_URL/credentials/nonce                    → nonce de un solo uso (paso 1)
POST $APP_URL/credentials/issue                    → emitir VC (paso 2, body: {holder_did, proof})
GET  $APP_URL/credentials?holder_did={did}         → metadatos de VCs del holder
POST $APP_URL/credentials/{credentialId}/revoke    → revocar una VC (204 ok, 404 no existe)
GET  $APP_URL/issuer/did                           → DID e info del emisor
GET  $APP_URL/issuer/did-document                  → DID Document W3C del emisor

# Identidad del emisor
ISSUER_DID=$ISSUER_DID
ISSUER_PUBLIC_KEY_HEX=$ISSUER_PUB_HEX

# Configurar en MainActivity.java:
#   private static final String ISSUER_URL = "$APP_URL";

# ─── Colección Postman ────────────────────────────────────────────────────────
# Importar el archivo: postman-collection.json
# Variables preconfiguradas en la colección:
#   BASE_URL    = $APP_URL
#   ISSUER_DID  = $ISSUER_DID
#   HOLDER_DID  = did:key:zQ3shTestHolder000000000000 (dummy, reemplazar con el DID real del holder)
#
# Flujo de prueba en Postman:
#   1. Ejecutar "GET Issuer DID"        → verifica el backend
#   2. Ejecutar "GET Nonce"             → obtiene nonce (se guarda automáticamente)
#   3. Generar PROOF_JWT con el script: ./scripts/gen-proof.sh {{NONCE}} {{HOLDER_DID}}
#      Pegar el JWT en la variable PROOF_JWT de la colección
#   4. Ejecutar "POST Issue VC"         → emite la VC (CREDENTIAL_ID se guarda automático)
#   5. Ejecutar "GET Credentials"       → lista credenciales del holder
#   6. Ejecutar "POST Revoke VC"        → revoca la VC
EOF

# ─── postman-collection.json ──────────────────────────────────────────────────
python3 - "$APP_URL" "$ISSUER_DID" "$PROJECT_ROOT/postman-collection.json" <<'PYEOF'
import sys, json, uuid

BASE_URL   = sys.argv[1]
ISSUER_DID = sys.argv[2]

HOLDER_DID_DUMMY = "did:key:zQ3shTestHolder000000000000"

def req(name, method, path, description, body=None, pre_script=None, test_script=None, params=None):
    url_parts = {"raw": BASE_URL + path, "host": [BASE_URL], "path": path.lstrip("/").split("/")}
    if params:
        url_parts["query"] = [{"key": k, "value": v} for k, v in params.items()]
    r = {
        "name": name,
        "request": {
            "method": method,
            "header": [{"key": "Content-Type", "value": "application/json"}] if body else [],
            "url": url_parts,
            "description": description,
        },
        "response": []
    }
    if body:
        r["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2), "options": {"raw": {"language": "json"}}}
    if pre_script:
        r["event"] = r.get("event", [])
        r["event"].append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pre_script.splitlines()}})
    if test_script:
        r["event"] = r.get("event", [])
        r["event"].append({"listen": "test", "script": {"type": "text/javascript", "exec": test_script.splitlines()}})
    return r

nonce_pre = """\
// Obtener nonce fresco antes de emitir la VC
pm.sendRequest({
    url: pm.collectionVariables.get('BASE_URL') + '/credentials/nonce?holder_did=' + pm.collectionVariables.get('HOLDER_DID'),
    method: 'GET'
}, function(err, res) {
    if (!err && res.code === 200) {
        var nonce = res.json().nonce;
        pm.collectionVariables.set('NONCE', nonce);
        console.log('✓ Nonce obtenido: ' + nonce);
        console.log('');
        console.log('⚠  Genera el PROOF_JWT antes de enviar:');
        console.log('   ./scripts/gen-proof.sh ' + nonce + ' ' + pm.collectionVariables.get('HOLDER_DID'));
        console.log('   Luego pega el resultado en la variable PROOF_JWT.');
    } else {
        console.error('Error obteniendo nonce: ' + (err || res.status));
    }
});"""

nonce_test = """\
pm.test("Status 200", () => pm.response.to.have.status(200));
pm.test("Contiene nonce", () => pm.expect(pm.response.json()).to.have.property('nonce'));
var nonce = pm.response.json().nonce;
pm.collectionVariables.set('NONCE', nonce);
console.log('Nonce guardado: ' + nonce);"""

issue_test = """\
pm.test("Status 200 - VC emitida", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
    var json = pm.response.json();
    pm.test("Contiene credentialId", () => pm.expect(json).to.have.property('credentialId'));
    pm.test("Contiene credential JWT", () => pm.expect(json).to.have.property('credential'));
    pm.collectionVariables.set('CREDENTIAL_ID', json.credentialId || '');
    console.log('✓ CREDENTIAL_ID guardado: ' + json.credentialId);
}"""

creds_test = """\
pm.test("Status 200", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
    var list = pm.response.json();
    pm.test("Es un array", () => pm.expect(list).to.be.an('array'));
    if (list.length > 0) {
        pm.test("Tiene credential_id", () => pm.expect(list[0]).to.have.property('credential_id'));
        pm.test("Tiene revoked", () => pm.expect(list[0]).to.have.property('revoked'));
        pm.collectionVariables.set('CREDENTIAL_ID', list[0].credential_id || pm.collectionVariables.get('CREDENTIAL_ID'));
        console.log('Credenciales encontradas: ' + list.length);
        console.log('Primera: ' + JSON.stringify(list[0], null, 2));
    }
}"""

revoke_test = """\
pm.test("204 No Content (revocada correctamente)", () => pm.response.to.have.status(204));
if (pm.response.code === 204) { console.log('✓ Credencial revocada: ' + pm.collectionVariables.get('CREDENTIAL_ID')); }
if (pm.response.code === 404) { console.log('⚠  Credencial no encontrada. Verifica CREDENTIAL_ID.'); }"""

did_test = """\
pm.test("Status 200", () => pm.response.to.have.status(200));
var json = pm.response.json();
pm.test("Contiene did", () => pm.expect(json).to.have.property('did'));
pm.test("DID comienza con did:key:", () => pm.expect(json.did).to.match(/^did:key:/));
pm.collectionVariables.set('ISSUER_DID', json.did);
console.log('Issuer DID: ' + json.did);"""

collection = {
    "info": {
        "_postman_id": str(uuid.uuid4()),
        "name": "DID Issuer API",
        "description": (
            "Colección generada automáticamente por deploy-azure.sh\n\n"
            "**Flujo completo:**\n"
            "1. GET Issuer DID → confirma backend activo\n"
            "2. GET Nonce → obtiene nonce (guardado en {{NONCE}})\n"
            "3. Generar PROOF_JWT externamente (ver gen-proof.sh)\n"
            "4. POST Issue VC → emite la VC ({{CREDENTIAL_ID}} se guarda automático)\n"
            "5. GET Credentials → lista VCs del holder\n"
            "6. POST Revoke VC → revoca la VC\n\n"
            "**Para generar PROOF_JWT:**\n"
            "```\n./scripts/gen-proof.sh {{NONCE}} {{HOLDER_DID}}\n```\n"
            "Pega el resultado en la variable PROOF_JWT de la colección."
        ),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
    },
    "variable": [
        {"key": "BASE_URL",       "value": BASE_URL,        "type": "string"},
        {"key": "ISSUER_DID",     "value": ISSUER_DID,      "type": "string"},
        {"key": "HOLDER_DID",     "value": HOLDER_DID_DUMMY,"type": "string", "description": "DID del holder — reemplazar con el DID real de la app Android"},
        {"key": "NONCE",          "value": "",              "type": "string", "description": "Se rellena automáticamente al ejecutar GET Nonce"},
        {"key": "CREDENTIAL_ID",  "value": "",              "type": "string", "description": "Se rellena automáticamente al ejecutar POST Issue VC"},
        {"key": "PROOF_JWT",      "value": "PENDING_GENERATION", "type": "string", "description": "JWT firmado por el holder — generar con gen-proof.sh o la app Android"},
    ],
    "item": [
        {
            "name": "🔑 Identidad del Issuer",
            "item": [
                req("GET Issuer DID", "GET", "/issuer/did",
                    "Retorna el DID del emisor y su clave pública secp256k1 comprimida.",
                    test_script=did_test),
                req("GET DID Document (W3C)", "GET", "/issuer/did-document",
                    "Retorna el DID Document completo en formato W3C con el método de verificación.",
                    test_script="""\
pm.test("Status 200", () => pm.response.to.have.status(200));
var json = pm.response.json();
pm.test("Tiene @context", () => pm.expect(json).to.have.property('@context'));
pm.test("Tiene verificationMethod", () => pm.expect(json).to.have.property('verificationMethod'));
pm.test("Tiene EcdsaSecp256k1", () => pm.expect(JSON.stringify(json)).to.include('EcdsaSecp256k1'));"""),
            ]
        },
        {
            "name": "📋 Flujo de Credencial",
            "item": [
                req("GET Nonce", "GET", "/credentials/nonce",
                    "Obtiene un nonce de un solo uso. El holder lo incluye en su Proof JWT firmado.\n\nEl nonce se guarda automáticamente en {{NONCE}}.",
                    params={"holder_did": "{{HOLDER_DID}}"},
                    test_script=nonce_test),
                req("POST Issue VC", "POST", "/credentials/issue",
                    (
                        "Emite una Verifiable Credential firmada por el issuer.\n\n"
                        "**Prerrequisito**: Generar {{PROOF_JWT}} con:\n"
                        "```\n./scripts/gen-proof.sh {{NONCE}} {{HOLDER_DID}}\n```\n"
                        "El pre-request script obtiene el nonce automáticamente y muestra el comando en la consola.\n\n"
                        "El campo `proof` es un JWT firmado con la clave secp256k1 del holder que contiene:\n"
                        "- `sub`: holder_did\n- `nonce`: el nonce obtenido\n- `aud`: DID del issuer\n\n"
                        "{{CREDENTIAL_ID}} se guarda automáticamente en la respuesta."
                    ),
                    body={
                        "holderDid": "{{HOLDER_DID}}",
                        "proof": "{{PROOF_JWT}}",
                        "credentialType": "UniversityDegreeCredential"
                    },
                    pre_script=nonce_pre,
                    test_script=issue_test),
                req("GET Credentials by Holder", "GET", "/credentials",
                    "Lista los metadatos de todas las VCs emitidas para un holder.\n\nNo expone el JWT completo, solo metadatos (credential_id, type, issued_at, expires_at, revoked).",
                    params={"holder_did": "{{HOLDER_DID}}"},
                    test_script=creds_test),
                req("POST Revoke VC", "POST", "/credentials/{{CREDENTIAL_ID}}/revoke",
                    "Revoca una VC por su ID.\n\n- `204 No Content` → revocada correctamente\n- `404 Not Found` → la credencial no existe\n\n{{CREDENTIAL_ID}} se rellena automáticamente después de POST Issue VC.",
                    test_script=revoke_test),
            ]
        }
    ]
}

with open(sys.argv[3], "w") as f:
    json.dump(collection, f, indent=2, ensure_ascii=False)
PYEOF
ok "Colección Postman generada: postman-collection.json"

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
echo "  │  GET  /credentials/nonce           (paso 1, pedir VC)  │"
echo "  │  POST /credentials/issue           (paso 2, emitir VC) │"
echo "  │  GET  /credentials?holder_did=     (metadatos)         │"
echo "  │  POST /credentials/{id}/revoke     (revocar VC)        │"
echo "  │  GET  /issuer/did                  (info del emisor)   │"
echo "  │  GET  /issuer/did-document         (DID Document W3C)  │"
echo "  │                                                         │"
if [ -n "$ISSUER_DID" ]; then
echo "  │  DID del emisor:                                        │"
printf "  │  %-55s│\n" "$ISSUER_DID"
echo "  │                                                         │"
fi
echo "  └─────────────────────────────────────────────────────────┘"
echo ""
echo "  Archivos generados para el equipo mobile:"
echo "    mobile-config.txt        — endpoints y DID del emisor"
echo "    postman-collection.json  — importar en Postman (File → Import)"
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
