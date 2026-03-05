# DID Mobile Wallet + Issuer Backend

Sistema de Identidad Descentralizada (DID) sin blockchain, compuesto por una wallet Android y un emisor de credenciales verificables.

---

## Actores

| Actor | Rol | Dónde vive |
|-------|-----|------------|
| **Holder** | Dueño de la identidad. Genera su clave, solicita credenciales y las presenta. | App Android |
| **Issuer** | Organización que emite credenciales verificables firmadas. | Backend Java |
| **Verifier** | Quien recibe y valida una presentación del holder. | Sistema externo (no incluido) |

El holder es el único custodio de sus credenciales. El issuer no las almacena.

---

## Flujos

### 1. Registro de identidad (solo en el dispositivo)

```
Holder (app)
──────────────────────────────────────────────────
1. Genera par de claves secp256k1
2. Protege la clave privada en hardware del dispositivo
3. Deriva su DID:key a partir de la clave pública
   → did:key:zQ3s...

No requiere red. No requiere backend.
```

### 2. Obtención de una Verifiable Credential

```
Holder (app)              Issuer (backend)
─────────────────         ──────────────────────────────────
                          Tiene su propio DID e identidad

1. GET /credentials/nonce ──────────────────────→
                          ←────────────── { nonce }

2. Firma un Proof JWT con su clave privada
   (incluye el nonce y los claims que quiere acreditar)

3. POST /credentials/issue ─────────────────────→
   { holder_did, proof }
                          Verifica: firma válida + nonce válido
                          Emite VC JWT firmada con su clave
                          Guarda solo metadatos (no el JWT)
                          ←────────────── { credential: <VC JWT> }

4. Almacena la VC cifrada en el dispositivo
```

### 3. Presentación ante un Verificador

```
Holder (app)              Verifier (externo)
─────────────────         ──────────────────────────────────
                          Solicita presentación + nonce

1. Toma sus VCs almacenadas
2. Construye una VP JWT que las empaqueta
3. La firma con su clave privada
4. Envía la VP al verificador ──────────────────→

                          Verifica:
                          - Firma del holder sobre la VP
                          - Firma del issuer sobre cada VC
                          - Vigencia y nonce
```

---

## Seguridad

### Clave privada del holder

La clave privada nunca sale del dispositivo en claro. Se protege en dos capas:

```
Clave privada secp256k1
    └── cifrada con AES-256/GCM
            └── cuya clave vive en Android Keystore (hardware)
                    ├── StrongBox (chip dedicado) — si el dispositivo lo tiene
                    └── TEE (zona aislada del SoC) — fallback en casi todos los Android modernos
```

La app detecta automáticamente el nivel disponible y usa el más seguro.

### Verifiable Credentials en disco

Las VCs se almacenan cifradas con `EncryptedSharedPreferences` (AES-256/GCM). Incluso con acceso físico al dispositivo o backup ADB, el contenido es ilegible.

### Custodia de credenciales

El issuer **no guarda el JWT de la VC**. Lo entrega una sola vez al holder y conserva solo un hash para poder revocarla en el futuro. El holder es el único custodio.

### Anti-replay

Cada solicitud de credencial requiere un nonce fresco del issuer. El nonce se consume al verificarlo — enviarlo dos veces es rechazado.

---

## Cómo funciona el Proof JWT

El Proof JWT demuestra que el holder **posee la clave privada** correspondiente a su DID, sin revelarla. Es el mecanismo central de autenticación del protocolo.

### Paso 1 — Generar el par de claves y el DID (una sola vez)

```
clave privada (secp256k1, 32 bytes)
        │
        ▼
clave pública comprimida (33 bytes)
        │
        ▼
multicodec prefix [0xe7, 0x01] + pub_bytes
        │
        ▼
base58btc encode → z<encoded>
        │
        ▼
DID = "did:key:z<encoded>"
```

El DID **es** la clave pública codificada. No se registra en ningún servidor — se resuelve localmente. Quien recibe el DID puede derivar la clave pública y verificar firmas sin contactar al issuer.

### Paso 2 — Pedir el nonce (usando el DID)

```
GET /credentials/nonce?holder_did=did:key:z...
→ { "nonce": "abc123" }
```

El nonce es de un solo uso y tiene expiración de 5 minutos. Protege contra replay attacks: si alguien intercepta el Proof JWT, no puede reutilizarlo porque el nonce ya fue consumido.

### Paso 3 — Firmar el Proof JWT (con la clave privada)

```
header  = { alg: ES256K, typ: openid4vci-proof+jwt, kid: "did:key:z...#z..." }
payload = {
    iss:             "did:key:z..."     ← quién firma
    aud:             "https://issuer"   ← a quién va dirigido
    nonce:           "abc123"           ← el nonce recibido
    credential_type: "UniversityDegreeCredential"
    subject_claims:  { givenName, familyName, email }
}
firma = ECDSA(SHA256(header.payload), clave_privada)
```

La firma cubre el `nonce` — si alguien modifica el nonce, la firma es inválida.

### Paso 4 — El backend verifica sin conocer la clave privada

```
1. Extrae el DID del campo iss
2. Deriva la clave pública del DID (es pública, está en el DID)
3. Verifica la firma ECDSA con esa clave pública
4. Verifica que el nonce coincida y no haya sido usado antes
5. Si todo ok → emite la VC firmada por el issuer
```

La clave privada **nunca sale del dispositivo**. El backend solo necesita la clave pública (obtenida del DID) para verificar.

### Flujo completo

```
App Android / Postman                  Issuer Backend
─────────────────────                  ──────────────────────────────
1. ./scripts/gen-holder.sh
   → genera clave privada + pública
   → deriva DID del holder
   → guarda en .holder-keys

2. GET /nonce?holder_did=did:key:z... ─────────────────────────────▶
                                       guarda nonce para ese DID
   ◀─ { nonce: "abc123" } ────────────────────────────────────────

3. ./scripts/gen-proof.sh <nonce>
   → lee clave privada de .holder-keys
   → firma JWT con nonce + subject_claims
   → imprime PROOF_JWT

4. POST /credentials/issue ────────────────────────────────────────▶
   { holder_did, proof: PROOF_JWT }
                                       verifica firma (clave pública del DID)
                                       verifica nonce (consumido, one-time)
                                       emite VC JWT firmada por el issuer
   ◀─ { credential: "eyJ..." } ───────────────────────────────────
```

### Para pruebas con Postman

```bash
# Paso 1 — generar identidad del holder (una sola vez)
./scripts/gen-holder.sh
# → imprime HOLDER_DID, guarda .holder-keys
# → copiar HOLDER_DID a la variable HOLDER_DID de la colección

# Paso 2 — obtener nonce (ejecutar "GET Nonce" en Postman)
# → NONCE se guarda automáticamente en la variable de colección

# Paso 3 — generar proof con el nonce
./scripts/gen-proof.sh <NONCE>
# → imprime PROOF_JWT
# → copiar a la variable PROOF_JWT de la colección

# Paso 4 — ejecutar "POST Issue VC" en Postman
```

---

## Formato de los mensajes

### Proof JWT — del holder al issuer
```json
{
  "iss": "did:key:zQ3s...",
  "aud": "http://issuer.example.com",
  "nonce": "abc123",
  "credential_type": "UniversityDegreeCredential",
  "subject_claims": { "givenName": "Juan", "familyName": "Pérez" }
}
```
*Firmado con la clave privada del holder (ES256K).*

### VC JWT — del issuer al holder
```json
{
  "iss": "did:key:zIssuer...",
  "sub": "did:key:zQ3s...",
  "vc": {
    "type": ["VerifiableCredential", "UniversityDegreeCredential"],
    "credentialSubject": { "id": "did:key:zQ3s...", "givenName": "Juan" }
  }
}
```
*Firmado con la clave privada del issuer (ES256K).*

### VP JWT — del holder al verifier
```json
{
  "iss": "did:key:zQ3s...",
  "aud": "did:web:verifier.example.com",
  "nonce": "verifier-nonce",
  "vp": {
    "type": ["VerifiablePresentation"],
    "verifiableCredential": ["<VC_JWT>"]
  }
}
```
*Firmado con la clave privada del holder (ES256K).*

---

## Estándares implementados

| Estándar | Para qué se usa |
|----------|-----------------|
| [W3C DID Core 1.0](https://www.w3.org/TR/did-core/) | Formato y resolución de DIDs |
| [did:key](https://w3c-ccg.github.io/did-method-key/) | Método DID autónomo — el DID se deriva directamente de la clave pública, sin registro externo |
| [W3C VC Data Model 1.1](https://www.w3.org/TR/vc-data-model/) | Estructura de las Verifiable Credentials y Presentations |
| [JWT VC/VP](https://www.w3.org/TR/vc-data-model/#json-web-token) | Encoding de VCs y VPs como tokens JWT compactos |
| [ES256K — RFC 8812](https://www.rfc-editor.org/rfc/rfc8812) | Algoritmo de firma: ECDSA con curva secp256k1 y hash SHA-256 |
| [OpenID4VCI (draft)](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html) | Protocolo de solicitud de credencial entre holder e issuer |

## Claves y su propósito

| Clave | Tipo | Quién la tiene | Para qué se usa |
|-------|------|----------------|-----------------|
| Clave privada del **holder** | secp256k1 (256 bits) | Solo el dispositivo Android | Firmar el Proof JWT y las VPs. Nunca sale del dispositivo. |
| Clave pública del **holder** | secp256k1 comprimida (33 bytes) | Embebida en el DID:key | Permite que cualquiera verifique sus firmas sin contactar al issuer. |
| Clave privada del **issuer** | secp256k1 (256 bits) | Solo el backend | Firmar las VCs que emite. Se genera una vez y se persiste. |
| Clave pública del **issuer** | secp256k1 comprimida (33 bytes) | Su DID Document | Permite que el verifier valide las VCs sin contactar al issuer. |
| Clave AES-256 (**wrapping**) | AES-256 simétrica | Android Keystore (hardware) | Cifrar la clave privada del holder en disco. Nunca sale del chip. |

La clave AES no tiene rol criptográfico en el protocolo DID — su único trabajo es proteger la clave secp256k1 mientras está almacenada en el dispositivo.

---

## Qué necesita el backend para operar

1. **Un par de claves secp256k1** — se genera automáticamente al primer inicio
2. **Un DID propio** — derivado de su clave pública (`did:key`)
3. **Endpoint de nonce** — protege contra replay attacks
4. **Endpoint de emisión** — verifica el proof y firma la VC
5. **Base de datos** — solo para metadatos de auditoría (H2 en dev, PostgreSQL en prod)

No se necesita blockchain, PKI propia ni registro DID externo. `did:key` se resuelve localmente a partir del propio DID.

---

## Endpoints del backend

| Método | URL | Descripción |
|--------|-----|-------------|
| `GET`  | `/credentials/nonce` | Nonce de un solo uso para el holder |
| `POST` | `/credentials/issue` | Recibe proof JWT, emite VC JWT |
| `GET`  | `/credentials?holder_did=` | Metadatos de VCs emitidas (sin contenido) |
| `GET`  | `/issuer/did` | DID e info del issuer |
| `GET`  | `/issuer/did-document` | DID Document del issuer |

---

## Estructura del proyecto

```
didbmo/
├── android/app/src/main/java/com/did/wallet/
│   ├── security/KeyManager.java          ← claves secp256k1 + Android Keystore
│   ├── did/DIDManager.java               ← construcción del DID:key
│   ├── credential/
│   │   ├── CredentialRequestBuilder.java ← proof JWT firmado
│   │   └── CredentialService.java        ← HTTP al issuer + almacenamiento cifrado
│   ├── presentation/VPBuilder.java       ← VP JWT firmada
│   └── ui/MainActivity.java             ← demo de los 3 flujos
│
└── backend/src/main/java/com/did/issuer/
    ├── config/IssuerKeyConfig.java       ← clave e identidad del issuer
    ├── controller/CredentialController.java
    ├── service/
    │   ├── NonceService.java             ← nonces single-use
    │   ├── ProofVerifier.java            ← verifica proof JWT
    │   └── CredentialIssuerService.java  ← emite y firma VCs
    └── model/CredentialRecord.java       ← metadatos de auditoría
```
