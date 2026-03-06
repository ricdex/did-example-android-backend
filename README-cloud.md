# Despliegue en Azure

---

## Por qué Azure Container Apps (no Azure Functions)

Azure Functions es serverless pero no es adecuado para Spring Boot:
- Spring Boot tiene un servidor HTTP embebido que no encaja en el modelo de función (handler por invocación)
- El almacenamiento de archivos es efímero (el PEM de la clave se pierde entre ejecuciones)
- Cold starts de Java Spring Boot en Consumption Plan superan los 30s

**Azure Container Apps** es la alternativa serverless para contenedores:
- Escala a 0 réplicas cuando no hay tráfico (coste cero en reposo)
- Usa el Dockerfile existente sin modificaciones al código
- Integración nativa con Key Vault y Table Storage mediante Managed Identity
- Elimina credenciales del código — el contenedor nunca ve contraseñas directamente

---

## Por qué Azure Table Storage (no PostgreSQL)

PostgreSQL en Azure tiene inconvenientes importantes para este proyecto:

| | PostgreSQL Flexible Server | Azure Table Storage |
|---|---|---|
| Tiempo de provisión | 5–10 minutos | Segundos |
| Costo mínimo | ~$15/mes (Burstable B1ms) | ~$0 para un demo |
| Modo serverless | No disponible en Azure | Nativo |
| Gestión | Requiere servidor activo | Totalmente gestionado |
| Autenticación | Usuario/contraseña | Managed Identity (RBAC) |

**Azure Table Storage** almacena los metadatos de las VCs emitidas (sin JPA, sin servidor):
- PartitionKey: `"credentials"` (fijo)
- RowKey: `credentialId` (= `urn:uuid:...`)
- Campos: `holderDid`, `issuerDid`, `credentialType`, `vcJwtHash`, `issuedAt`, `expiresAt`, `revoked`

### Lógica de activación (sin cambios al código)

```
Sin AZURE_STORAGE_ACCOUNT_NAME (local dev)
  → azure.storage.account-name = "" → AzureTableCredentialStore: NO se crea
  → JpaCredentialStore SE crea → usa H2 vía JPA (comportamiento actual)

Con AZURE_STORAGE_ACCOUNT_NAME=didissuerabc (Azure Container Apps)
  → AzureTableCredentialStore SE crea → usa Managed Identity para Table Storage
  → JpaCredentialStore NO se crea
  → H2 sigue inicializando (JPA en classpath) pero nadie escribe en él → inofensivo
```

---

## Arquitectura en Azure

```
                    ┌─────────────────────────────────────────────┐
                    │           Azure Container Apps               │
                    │                                              │
  Internet ────────▶│  did-issuer-app  (Spring Boot, Java 17)     │
   HTTPS            │  • min replicas: 0  (escala a 0)            │
                    │  • max replicas: 3                           │
                    └───────────┬─────────────────────┬───────────┘
                                │                     │
                  Managed Identity             Managed Identity
                    (sin password)              (sin password)
                                │                     │
                    ┌───────────▼───────┐   ┌─────────▼───────────┐
                    │   Azure Key Vault  │   │  Azure Table Storage │
                    │                   │   │                      │
                    │  issuer-key-pem   │   │  tabla: credentials  │
                    │  storage-conn     │   │  tabla: holderDids   │
                    └───────────────────┘   └─────────────────────┘

                    ┌───────────────────┐
                    │  Azure Container  │
                    │  Registry (ACR)   │
                    │  did-issuer:latest│
                    └───────────────────┘
```

### Flujo de secretos (sin contraseñas en el código)

```
Key Vault
  └── issuer-key-pem  (PEM en base64 de la clave secp256k1 del emisor)
         │
         │  Container Apps lo inyecta como env var
         │  usando la Managed Identity (RBAC: Key Vault Secrets User)
         ▼
Container App
  └── ISSUER_KEY_PEM  → IssuerKeyConfig lo decodifica (base64 → PEM)

Table Storage
  └── tabla: credentials
         │
         │  AzureTableCredentialStore usa DefaultAzureCredential
         │  (RBAC: Storage Table Data Contributor sobre el Storage Account)
         ▼
Container App
  └── AZURE_STORAGE_ACCOUNT_NAME  → AzureTableCredentialStore construye el TableClient
```

---

## Requisitos

- **Azure CLI** instalado y autenticado: `az login`
- **openssl** (disponible en macOS/Linux por defecto)
- **python3** (para generar el sufijo único)
- Una **suscripción Azure** activa

Instalar Azure CLI:
```bash
# macOS
brew install azure-cli

# Linux
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
```

---

## Primer despliegue

```bash
./scripts/deploy-azure.sh
```

El script es idempotente: si se interrumpe y se vuelve a ejecutar, reutiliza los recursos ya creados. El sufijo único se guarda en `.azure-suffix`.

### Variables de entorno opcionales

```bash
# Cambiar nombre del resource group o región
RESOURCE_GROUP=mi-rg LOCATION=westeurope ./scripts/deploy-azure.sh
```

---

## Qué hace el script paso a paso

| Paso | Acción | Por qué |
|------|--------|---------|
| 1 | Crear Resource Group | Contenedor lógico para todos los recursos |
| 2 | Crear Azure Container Registry | Almacena la imagen Docker de forma privada |
| 3 | Build de la imagen en ACR | No requiere Docker local — ACR compila en la nube |
| 4 | Crear Key Vault | Almacena la clave privada del emisor de forma segura |
| 5 | Generar clave secp256k1 del emisor | Clave privada nunca toca disco local, va directo a Key Vault |
| 6 | Crear Storage Account + tabla `credentials` | Persistencia serverless de metadatos VC (~$0 para un demo) |
| 7 | Crear Managed Identity | Identidad que el contenedor usa ante Key Vault y Table Storage |
| 8 | Asignar roles RBAC | `Key Vault Secrets User` + `Storage Table Data Contributor` — mínimo privilegio |
| 9 | Crear Container Apps Environment | Plataforma serverless donde vive el contenedor |
| 10 | Desplegar Container App | El backend arranca con los secretos inyectados |
| 11 | Actualizar URL del emisor | El DID del issuer incluye su propia URL pública |

Duración estimada: **3–5 minutos** (sin PostgreSQL, el paso más lento era la BD).

---

## Outputs al terminar el despliegue

Al finalizar, el script imprime en pantalla los endpoints listos para el equipo mobile y genera dos archivos:

- **`mobile-config.txt`** — endpoints y DID del emisor, listo para compartir con el dev Android
- **`.azure-deploy-output.env`** — todos los nombres de recursos para operaciones posteriores

Ejemplo del bloque de endpoints que aparece en pantalla:
```
┌─────────────────────────────────────────────────────────┐
│  ENDPOINTS PARA EL EQUIPO MOBILE                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Base URL:                                              │
│  https://did-issuer-app.azurecontainerapps.io           │
│                                                         │
│  — Registro de DID ──────────────────────────────────  │
│  POST /dids/register               (registrar DID)     │
│  GET  /dids/{did}                  (estado del DID)    │
│  POST /dids/{did}/invalidate       (invalidar DID)     │
│  GET  /clients/{clientId}/dids     (DIDs del cliente)  │
│                                                         │
│  — Credenciales ─────────────────────────────────────  │
│  GET  /credentials/nonce           (paso 1, pedir VC)  │
│  POST /credentials/issue           (paso 2, emitir VC) │
│  POST /credentials/verify          (verificar VP JWT)  │
│  GET  /credentials?holder_did=     (metadatos)         │
│  POST /credentials/{id}/revoke     (revocar VC)        │
│                                                         │
│  — Identidad del issuer ─────────────────────────────  │
│  GET  /issuer/did                  (info del emisor)   │
│  GET  /issuer/did-document         (DID Document W3C)  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Verificación post-despliegue

```bash
# Leer URL del despliegue
source .azure-deploy-output.env

# Verificar que el backend responde
curl $APP_URL/issuer/did | jq .

# Ver el archivo para el equipo mobile
cat mobile-config.txt

# Ejecutar las pruebas de integración completas contra Azure
./scripts/run-tests.sh cloud $APP_URL

# Ver logs en tiempo real
az containerapp logs show \
  -g $RESOURCE_GROUP -n $ACA_NAME --follow
```

---

## Redesplegar (actualización de código)

Cuando hay cambios en el backend, basta volver a ejecutar el script. Detecta que los recursos ya existen y solo actualiza la imagen:

```bash
./scripts/deploy-azure.sh
```

El script re-construye la imagen en ACR y actualiza el Container App.

---

## Variables de entorno en producción

El Container App recibe estas variables automáticamente (gestionadas por el script):

| Variable | Fuente | Propósito |
|----------|--------|-----------|
| `ISSUER_KEY_PEM` | Key Vault secret `issuer-key-pem` | PEM base64 de la clave secp256k1 del emisor |
| `AZURE_STORAGE_CONNECTION_STRING` | Key Vault secret `storage-connection-string` | Connection string del Storage Account — activa autenticación por clave |
| `AZURE_STORAGE_ACCOUNT_NAME` | Script (nombre del Storage Account) | Activa `AzureTableCredentialStore` |
| `ISSUER_BASE_URL` | Script (FQDN de Container Apps) | URL pública del emisor para el DID Document |
| `H2_CONSOLE_ENABLED` | Script (`false`) | Desactiva la consola H2 en producción |

### Estrategia de autenticación en Table Storage

`AzureTableCredentialStore` usa connection string en prioridad sobre Managed Identity:
```
AZURE_STORAGE_CONNECTION_STRING presente → autenticación por account key (más compatible)
AZURE_STORAGE_CONNECTION_STRING ausente  → DefaultAzureCredential (Managed Identity)
```

La connection string se genera en el deploy script, se almacena en Key Vault como secret `storage-connection-string`, y se inyecta vía `secretref:` al contenedor. La clave nunca aparece en código ni en variables directas del Container App.

> Las variables `SPRING_DATASOURCE_*`, `SPRING_DATASOURCE_DRIVER` y `JPA_DIALECT` ya no se usan en producción — la persistencia la gestiona `AzureTableCredentialStore` directamente.

---

## Costos estimados

Con `min-replicas: 0`, cuando no hay tráfico no hay cómputo activo.

| Recurso | SKU | Costo estimado/mes |
|---------|-----|-------------------|
| Container Apps | Consumption (escala a 0) | ~$0–5 en uso bajo |
| Azure Table Storage | Standard LRS | ~$0 para un demo (<1 GB) |
| Key Vault | Standard | ~$0.03 por 10k operaciones |
| Container Registry | Basic | ~$5 |
| **Total** | | **~$5–10/mes en uso bajo** |

Comparado con la arquitectura anterior (con PostgreSQL Flexible Server ~$15–20/mes), el ahorro es de **~$15/mes**.

---

## Destruir todos los recursos

```bash
source .azure-deploy-output.env
az group delete -n $RESOURCE_GROUP --yes --no-wait
rm -f .azure-suffix .azure-deploy-output.env
```

Esto elimina **todos** los recursos del Resource Group incluyendo la clave privada del emisor y los datos de Table Storage.

---

## Diferencias entre local y producción

| Aspecto | Local (dev) | Azure (producción) |
|---------|-------------|-------------------|
| Persistencia de VCs | H2 embebida vía JPA (`JpaCredentialStore`) | Azure Table Storage (`AzureTableCredentialStore`) |
| Activación del store | `AZURE_STORAGE_ACCOUNT_NAME` ausente/vacío | `AZURE_STORAGE_ACCOUNT_NAME=didissuer<suffix>` |
| Autenticación al store | N/A (H2 embebida) | Managed Identity (sin contraseña) |
| Clave del emisor | Archivo `./data/issuer_key.pem` | Key Vault secret (base64) |
| Escalado | 1 instancia fija | 0–3 réplicas automático |
| Consola H2 | Disponible en `/h2-console` | Desactivada (`H2_CONSOLE_ENABLED=false`) |
| URL del emisor | `http://localhost:8080` | `https://<fqdn>.azurecontainerapps.io` |

El código no cambia entre entornos — todo se controla por variables de entorno.
