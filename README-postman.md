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

### PASO 2 — Obtener nonce

Ejecutar **GET Nonce** en la carpeta `📋 Flujo de Credencial`.

Respuesta esperada:
```json
{ "nonce": "abc123..." }
```

La variable `NONCE` se guarda automáticamente en la colección.

---

### PASO 3 — Generar el Proof JWT

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

### PASO 4 — Emitir la VC

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

### PASO 5 — Ver metadatos de credenciales

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

### PASO 6 — Revocar la VC

Ejecutar **POST Revoke VC**.

Usa `{{CREDENTIAL_ID}}` guardado en el paso anterior.

- `204 No Content` → revocada correctamente
- `404 Not Found` → credencial no encontrada (verificar `CREDENTIAL_ID`)

---

## Variables de la colección

| Variable | Cómo se llena | Descripción |
|----------|---------------|-------------|
| `BASE_URL` | Automático (deploy) | URL base del backend en Azure |
| `ISSUER_DID` | Auto (GET Issuer DID) | DID del emisor |
| `HOLDER_DID` | Manual (`gen-holder.sh`) | DID del holder de prueba |
| `NONCE` | Auto (GET Nonce) | Nonce de un solo uso |
| `CREDENTIAL_ID` | Auto (POST Issue VC) | ID de la VC emitida (`urn:uuid:...`) |
| `PROOF_JWT` | Manual (`gen-proof.sh`) | JWT firmado por el holder |

---

## Diagrama del flujo

```
Terminal                    Postman
────────────────────────    ──────────────────────────────────────
gen-holder.sh
→ HOLDER_DID ──────────────▶ Variable HOLDER_DID

                             GET Issuer DID  (verifica backend)
                             GET Nonce       → Variable NONCE

gen-proof.sh <NONCE>
→ PROOF_JWT ───────────────▶ Variable PROOF_JWT

                             POST Issue VC   → Variable CREDENTIAL_ID
                             GET Credentials (ver metadatos)
                             POST Revoke VC  (204 ok)
```

---

## Errores comunes

| Error | Causa | Solución |
|-------|-------|----------|
| `401 nonce inválido` | PROOF_JWT expirado o nonce ya usado | Repetir PASO 2 y PASO 3 |
| `400 proof inválido` | PROOF_JWT no corresponde al HOLDER_DID | Verificar que ambas variables vienen del mismo `gen-holder.sh` |
| `404 en Revoke` | CREDENTIAL_ID vacío o incorrecto | Ejecutar GET Credentials para ver el ID real |
| `PENDING_GENERATION` en PROOF_JWT | No se ejecutó `gen-proof.sh` | Ejecutar PASO 3 |
