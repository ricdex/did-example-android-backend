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
- Integración nativa con Key Vault mediante Managed Identity
- Elimina credenciales del código — el contenedor nunca ve las contraseñas directamente

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
                  Managed Identity                JDBC/SSL
                    (sin password)                    │
                                │                     ▼
                    ┌───────────▼───────┐   ┌─────────────────────┐
                    │   Azure Key Vault  │   │  Azure PostgreSQL    │
                    │                   │   │  Flexible Server     │
                    │  issuer-key-pem   │   │  did_issuer DB       │
                    │  pg-password      │   │  (Burstable B1ms)    │
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
  ├── issuer-key-pem  (PEM en base64 de la clave secp256k1 del emisor)
  └── pg-password     (contraseña generada al desplegar)
         │
         │  Container Apps los inyecta como env vars
         │  usando la Managed Identity (RBAC: Key Vault Secrets User)
         ▼
Container App
  ├── ISSUER_KEY_PEM          → IssuerKeyConfig lo decodifica (base64 → PEM)
  └── SPRING_DATASOURCE_PASSWORD → Spring lo usa para conectar a PostgreSQL
```

---

## Requisitos

- **Azure CLI** instalado y autenticado: `az login`
- **openssl** (disponible en macOS/Linux por defecto)
- **python3** (para generar el sufijo único y contraseñas)
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
| 4 | Crear Key Vault | Almacena secretos de forma segura |
| 5 | Generar clave secp256k1 del emisor | Clave privada nunca toca disco local, va directo a Key Vault |
| 6 | Crear PostgreSQL Flexible Server | Base de datos gestionada en lugar de H2 de desarrollo |
| 7 | Crear Managed Identity | Identidad que el contenedor usa para autenticarse ante Key Vault |
| 8 | Asignar rol Key Vault Secrets User | Permisos mínimos (RBAC) — solo lectura de secrets |
| 9 | Crear Container Apps Environment | Plataforma serverless donde vive el contenedor |
| 10 | Desplegar Container App | El backend arranca con los secretos inyectados |
| 11 | Actualizar URL del emisor | El DID del issuer incluye su propia URL pública |
| 12 | Guardar outputs | Escribe `.azure-deploy-output.env` con todas las URLs y nombres |

Duración estimada: **12–20 minutos** (PostgreSQL es el paso más lento).

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
│  Nonce (paso 1 para solicitar VC):                      │
│  GET https://.../credentials/nonce                      │
│                                                         │
│  Emisión de VC (paso 2):                                │
│  POST https://.../credentials/issue                     │
│                                                         │
│  Metadatos de VCs del holder:                           │
│  GET https://.../credentials?holder_did={did}           │
│                                                         │
│  DID del emisor (para configurar la app):               │
│  GET https://.../issuer/did                             │
│                                                         │
│  DID del emisor (para hardcodear en la app):            │
│  did:key:zQ3s...                                        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Verificación post-despliegue

```bash
# Leer URL del despliegue
source .azure-deploy-output.env

# Verificar que el backend responde (el script ya espera el cold start)
curl $APP_URL/issuer/did | jq .

# Ver el archivo para el equipo mobile
cat mobile-config.txt

# Ejecutar las pruebas de integración completas contra Azure
./scripts/test-integration.sh $APP_URL

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
| `ISSUER_KEY_PEM` | Key Vault secret | PEM base64 de la clave secp256k1 del emisor |
| `SPRING_DATASOURCE_URL` | Script (JDBC URL de PostgreSQL) | Conexión a la base de datos |
| `SPRING_DATASOURCE_USERNAME` | Script | Usuario de PostgreSQL |
| `SPRING_DATASOURCE_PASSWORD` | Key Vault secret | Contraseña de PostgreSQL |
| `SPRING_DATASOURCE_DRIVER` | Script | `org.postgresql.Driver` |
| `JPA_DIALECT` | Script | `org.hibernate.dialect.PostgreSQLDialect` |
| `ISSUER_BASE_URL` | Script (FQDN de Container Apps) | URL pública del emisor para el DID Document |
| `H2_CONSOLE_ENABLED` | Script (`false`) | Desactiva la consola H2 en producción |

---

## Costos estimados

Con `min-replicas: 0`, cuando no hay tráfico no hay cómputo activo.

| Recurso | SKU | Costo estimado/mes |
|---------|-----|-------------------|
| Container Apps | Consumption (escala a 0) | ~$0–5 en uso bajo |
| PostgreSQL | Burstable B1ms | ~$15–20 |
| Key Vault | Standard | ~$0.03 por 10k operaciones |
| Container Registry | Basic | ~$5 |
| **Total** | | **~$20–30/mes en uso bajo** |

Para entornos de desarrollo o prueba donde PostgreSQL no está siempre activo, considerar detenerlo manualmente:
```bash
az postgres flexible-server stop -g $RESOURCE_GROUP -n $PG_SERVER
az postgres flexible-server start -g $RESOURCE_GROUP -n $PG_SERVER
```

---

## Destruir todos los recursos

```bash
source .azure-deploy-output.env
az group delete -n $RESOURCE_GROUP --yes --no-wait
rm -f .azure-suffix .azure-deploy-output.env
```

Esto elimina **todos** los recursos del Resource Group incluyendo la clave privada del emisor y la base de datos.

---

## Diferencias entre local y producción

| Aspecto | Local (dev) | Azure (producción) |
|---------|-------------|-------------------|
| Base de datos | H2 embebida (archivo) | PostgreSQL gestionado |
| Clave del emisor | Archivo `./data/issuer_key.pem` | Key Vault secret (base64) |
| Escalado | 1 instancia fija | 0–3 réplicas automático |
| Consola H2 | Disponible en `/h2-console` | Desactivada |
| URL del emisor | `http://localhost:8080` | `https://<fqdn>.azurecontainerapps.io` |

El código no cambia entre entornos — todo se controla por variables de entorno.
