# Guía de pruebas con Postman

---

## Requisitos

- **Postman** (escritorio o web)
- **python3** con `pip install cryptography`
- El archivo `postman-collection.json` en la raíz del proyecto (generado por `deploy-azure.sh`)

---

## Importar la colección

1. Abrir Postman
2. `File → Import`
3. Seleccionar `postman-collection.json`
4. La colección aparece con todas las variables y requests preconfigurados

---

## Flujo completo de prueba

### PASO 0 — Generar identidad del holder (una sola vez)

```bash
./scripts/gen-holder.sh
```

Salida:
```
HOLDER_DID=did:key:zQ3sh...
HOLDER_PUBLIC_KEY_HEX=02...

✓ Claves guardadas en: .holder-keys
```

En Postman: `Edit Collection → Variables → HOLDER_DID` → pegar el valor impreso.

> `.holder-keys` contiene la clave privada. No commitear al repo.

---

### PASO 1 — Verificar el backend

Ejecutar **GET Issuer DID** en la carpeta `🔑 Identidad del Issuer`.

Respuesta esperada:
```json
{ "did": "did:key:z...", "public_key_hex": "03..." }
```

La variable `ISSUER_DID` se guarda automáticamente.

---

### PASO 2 — Registrar el DID en el issuer

Ejecutar **POST Register DID** (o hacer la llamada manualmente):

```
POST /dids/register
Content-Type: application/json

{
  "client_id": "user@example.com",
  "did": "{{HOLDER_DID}}"
}
```

Respuesta esperada:
```json
{
  "did": "did:key:zQ3sh...",
  "client_id": "user@example.com",
  "active": true,
  "registered_at": "2026-..."
}
```

> Este paso solo es necesario la primera vez. Es idempotente — registrar el mismo DID dos veces devuelve el mismo resultado.

> Si el DID **no está registrado**, el siguiente paso (`GET Nonce`) devuelve `400 Bad Request`.

---

### PASO 3 — Obtener nonce

Ejecutar **GET Nonce** en la carpeta `📋 Flujo de Credencial`.

El endpoint requiere el DID del holder:
```
GET /credentials/nonce?holder_did={{HOLDER_DID}}
```

Respuesta esperada:
```json
{ "nonce": "abc123..." }
```

La variable `NONCE` se guarda automáticamente en la colección.

---

### PASO 4 — Generar el Proof JWT

Copiar el `NONCE` de la consola de Postman y ejecutar:

```bash
./scripts/gen-proof.sh <NONCE>
```

Salida:
```
HOLDER_DID=did:key:zQ3sh...
PROOF_JWT=eyJhbGci...
```

En Postman: `Edit Collection → Variables → PROOF_JWT` → pegar el JWT completo.

> El JWT tiene validez de 5 minutos. Si expira, repetir este paso con un nonce nuevo.

---

### PASO 5 — Emitir la VC

Ejecutar **POST Issue VC**.

Body enviado automáticamente:
```json
{
  "holder_did": "{{HOLDER_DID}}",
  "proof": "{{PROOF_JWT}}",
  "credentialType": "UniversityDegreeCredential"
}
```

Respuesta esperada:
```json
{ "credential": "eyJ..." }
```

La variable `CREDENTIAL_ID` se extrae automáticamente del campo `jti` dentro del JWT de la credencial.

---

### PASO 6 — Ver metadatos de credenciales

Ejecutar **GET Credentials by Holder**.

Respuesta esperada:
```json
[
  {
    "credential_id": "urn:uuid:...",
    "credential_type": "UniversityDegreeCredential",
    "issued_at": "2026-...",
    "expires_at": "2027-...",
    "revoked": false
  }
]
```

---

### PASO 7 — Generar y verificar la VP

El holder construye una Verifiable Presentation (VP) que contiene la VC emitida, la firma con su clave privada y la envía al backend para verificación.

**7a. Copiar el VC JWT** obtenido en PASO 5 (el valor del campo `credential`).

**7b. Generar la VP JWT:**

```bash
./scripts/gen-vp.sh <VC_JWT>
```

Salida:
```
VP_JWT=eyJhbGci...

# ─── Verificando contra el backend ─────────────────────────────────────
# POST https://.../credentials/verify

HTTP 200

{
  "valid": true,
  "holder_did": "did:key:zQ3sh...",
  "credentials": [
    {
      "credential_id": "urn:uuid:...",
      "type": "UniversityDegreeCredential",
      "subject": { "givenName": "...", "familyName": "...", "email": "..." },
      "expires_at": "2027-..."
    }
  ]
}

✓ VP válida
```

El script envía la VP automáticamente. Para probarla también desde Postman:

**7c. En Postman**, ejecutar **POST Verify VP**:

```
POST /credentials/verify
Content-Type: application/json

{
  "vp_jwt": "<VP_JWT del paso 7b>"
}
```

Respuesta esperada (`200 OK`):
```json
{
  "valid": true,
  "holder_did": "did:key:zQ3sh...",
  "credentials": [
    {
      "credential_id": "urn:uuid:...",
      "type": "UniversityDegreeCredential",
      "subject": { "givenName": "Ana", "familyName": "García", "email": "ana@example.com" },
      "expires_at": "2027-..."
    }
  ]
}
```

Respuesta si la VC fue revocada (`400 Bad Request`):
```json
{ "valid": false, "reason": "VC revocada: urn:uuid:..." }
```

> La VP tiene validez de 5 minutos (`exp: now + 300`). Si expira, volver a ejecutar `gen-vp.sh`.

---

### PASO 8 — Revocar la VC

Ejecutar **POST Revoke VC**.

Usa `{{CREDENTIAL_ID}}` guardado en el paso anterior.

- `204 No Content` → revocada correctamente
- `404 Not Found` → credencial no encontrada (verificar `CREDENTIAL_ID`)

Después de revocar, repetir PASO 7 para confirmar que la VP devuelve `valid: false`.

---

## Variables de la colección

| Variable | Cómo se llena | Descripción |
|----------|---------------|-------------|
| `BASE_URL` | Automático (deploy) | URL base del backend en Azure |
| `ISSUER_DID` | Auto (GET Issuer DID) | DID del emisor |
| `HOLDER_DID` | Manual (`gen-holder.sh`) | DID del holder de prueba |
| `NONCE` | Auto (GET Nonce) | Nonce de un solo uso |
| `PROOF_JWT` | Manual (`gen-proof.sh`) | JWT de prueba firmado por el holder |
| `CREDENTIAL_ID` | Auto (POST Issue VC) | ID de la VC emitida (`urn:uuid:...`) |
| `VP_JWT` | Manual (`gen-vp.sh <VC_JWT>`) | VP JWT firmada por el holder |

---

## Diagrama del flujo

```
Terminal                    Postman
────────────────────────    ──────────────────────────────────────
gen-holder.sh
→ HOLDER_DID ──────────────▶ Variable HOLDER_DID

                             GET Issuer DID      (verifica backend)
                             POST Register DID   (registra DID del holder)
                             GET Nonce           → Variable NONCE

gen-proof.sh <NONCE>
→ PROOF_JWT ───────────────▶ Variable PROOF_JWT

                             POST Issue VC       → Variable CREDENTIAL_ID
                             GET Credentials     (ver metadatos)

gen-vp.sh <VC_JWT>
→ VP_JWT ──────────────────▶ Variable VP_JWT
  (envía automáticamente
   al backend y muestra
   el resultado)
                             POST Verify VP      (200 valid:true)
                             POST Revoke VC      (204 ok)
                             POST Verify VP      (400 valid:false — revocada)
```

---

## Errores comunes

| Error | Causa | Solución |
|-------|-------|----------|
| `400 DID no registrado` en GET Nonce | El DID no fue registrado antes de pedir el nonce | Ejecutar PASO 2 (POST Register DID) |
| `400 DID invalidado` en GET Nonce | El DID fue invalidado (dispositivo perdido) | Registrar un nuevo DID con `gen-holder.sh` + PASO 2 |
| `400 nonce inválido` | PROOF_JWT expirado o nonce ya usado | Repetir PASO 3 y PASO 4 |
| `400 proof inválido` | PROOF_JWT no corresponde al HOLDER_DID | Verificar que ambas variables vienen del mismo `gen-holder.sh` |
| `404 en Revoke` | CREDENTIAL_ID vacío o incorrecto | Ejecutar GET Credentials para ver el ID real |
| `PENDING_GENERATION` en PROOF_JWT | No se ejecutó `gen-proof.sh` | Ejecutar PASO 4 |
| `400 VP expirada` | VP_JWT tiene más de 5 minutos | Volver a ejecutar `gen-vp.sh` |
| `400 VC revocada` en Verify VP | La VC incluida en la VP fue revocada | Emitir una nueva VC (PASOS 3–5) |
| `400 VC expirada` en Verify VP | La VC del holder expiró (TTL: 24h) | Emitir una nueva VC (PASOS 3–5) |
| `400 DID inactivo` en Verify VP | El DID del holder fue invalidado | Registrar nuevo DID con `gen-holder.sh` + PASO 2 |
