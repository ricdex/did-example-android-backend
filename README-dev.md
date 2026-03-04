# Guía de desarrollo y ejecución

---

## Requisitos

**Backend:** Docker (recomendado), o Java 17+ y Maven 3.9+.

**Tests curl:** `curl`, `jq`, `python3` con `pip3 install cryptography`.

**Android:** Android Studio, minSdk 26 (Android 8.0).

---

## Levantar el backend

```bash
# Con Docker (recomendado — no requiere Java ni Maven locales)
./scripts/start.sh docker

# Con Java + Maven local
./scripts/start.sh

# Detener Docker
./scripts/start.sh stop
```

Al primer inicio genera automáticamente:
- `./data/issuer_key.pem` — clave secp256k1 del issuer
- `./data/did_issuer.mv.db` — base de datos H2

Ambas persisten entre reinicios.

---

## Ejecutar los tests

### Tests de integración Java (contra servidor en memoria)

No requieren el backend corriendo — Spring Boot levanta uno interno.

```bash
# Requiere Docker (no Maven local)
cd backend
docker run --rm \
  -v "$(pwd)":/app \
  -v did-maven-cache:/root/.m2 \
  -w /app \
  maven:3.9-eclipse-temurin-17 \
  mvn verify --no-transfer-progress

# Con Maven local
mvn verify
```

Reporte generado en: `backend/target/reports/test-report-java-<timestamp>.md`

### Tests de integración curl (contra backend en ejecución)

```bash
# Levantar el backend primero
./scripts/start.sh docker

# Ejecutar los tests
./scripts/test-integration.sh

# Contra otra URL
./scripts/test-integration.sh http://192.168.1.10:8080
```

Reporte generado en: `reports/test-report-curl-<timestamp>.md`

---

## Qué valida cada prueba de integración

### Java — `CredentialFlowIntegrationTest`

| # | Nombre | Qué verifica |
|---|--------|-------------|
| 1 | `issuerDIDEndpoint` | `GET /issuer/did` devuelve `did` con prefijo `did:key:z` y `public_key_hex` de 66 caracteres (clave comprimida secp256k1). |
| 2 | `issuerDIDDocument` | `GET /issuer/did-document` devuelve un DID Document W3C válido con `@context`, `verificationMethod`, `assertionMethod` y tipo `EcdsaSecp256k1VerificationKey2019`. |
| 3 | `getNonce` | `GET /credentials/nonce` devuelve un nonce no vacío de un solo uso. |
| 4 | `issueCredential` | Flujo completo: genera un par secp256k1 real, obtiene nonce, construye y firma un Proof JWT (ES256K), llama a `POST /credentials/issue` y verifica que la VC devuelta sea un JWT de 3 partes con `iss`, `sub`, `UniversityDegreeCredential` y los claims del holder. |
| 5 | `rejectInvalidNonce` | `POST /credentials/issue` con un nonce que nunca existió devuelve HTTP 400 con mensaje de error que menciona "nonce". |
| 6 | `rejectInvalidSignature` | `POST /credentials/issue` con un Proof JWT firmado con la clave de un atacante (no coincide con el DID declarado) devuelve HTTP 400 con mensaje de error que menciona "firma". |
| 7 | `listCredentialMetadata` | `GET /credentials?holder_did=` devuelve los metadatos de la VC emitida en el test 4 (`credential_id`, `credential_type`, `issued_at`, `expires_at`, `revoked`) y confirma que el JWT completo nunca aparece en la respuesta. |

### curl — `scripts/test-integration.sh` (32 aserciones)

| Flujo | Aserciones |
|-------|-----------|
| **Flujo 1 — Identidad del issuer** | `GET /issuer/did`: HTTP 200, campo `did` presente, comienza con `did:key:z`, campo `public_key_hex` presente. `GET /issuer/did-document`: HTTP 200, contiene `@context`, `verificationMethod`, `EcdsaSecp256k1VerificationKey2019`. |
| **Flujo 2 — Emisión de VC** | `GET /credentials/nonce`: HTTP 200, nonce presente. `POST /credentials/issue`: HTTP 200, campo `credential` presente, es un JWT (3 partes separadas por `.`). |
| **Flujo 2b — Seguridad anti-replay** | Mismo nonce enviado dos veces: segunda vez HTTP 400. Nonce que nunca existió: HTTP 400. |
| **Flujo 3 — Metadatos del holder** | `GET /credentials?holder_did=`: HTTP 200, al menos 1 registro, campos `credential_id`, `credential_type`, `issued_at`, `expires_at`, `revoked` presentes, campo `credential` (JWT) ausente. |

---

## Configurar Android

Abrir la carpeta `android/` en Android Studio.

Ajustar la URL del backend en `MainActivity.java`:

```java
private static final String ISSUER_URL = "http://10.0.2.2:8080"; // emulador
// En dispositivo físico: usar la IP local de la máquina con el backend
```

---

## Invocaciones curl manuales

```bash
BASE=http://localhost:8080

# ── Flujo 1: Identidad del Issuer ────────────────────────────────────────────

curl $BASE/issuer/did | jq .
curl $BASE/issuer/did-document | jq .

# ── Flujo 2: Obtener una VC ──────────────────────────────────────────────────

# Paso 1 — Nonce (válido 5 min, de un solo uso)
curl $BASE/credentials/nonce | jq .

# Paso 2 — Flujo completo con firma real (ejecuta todo automáticamente)
./scripts/test-integration.sh

# Paso 3 — Metadatos de VCs del holder
curl "$BASE/credentials?holder_did=did:key:zQ3s..." | jq .

# ── Seguridad ─────────────────────────────────────────────────────────────────

# Nonce reutilizado → 400
NONCE=$(curl -s $BASE/credentials/nonce | jq -r '.nonce')
# Enviar el mismo nonce dos veces en el proof → segunda petición rechazada
```
