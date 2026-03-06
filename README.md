# DID Mobile Wallet — POC Backend

Sistema de Identidad Descentralizada (DID) sin blockchain, compuesto por una wallet Android y un backend Java.

---

## Actores

| Actor | Rol | Dónde vive |
|-------|-----|------------|
| **Holder** | Dueño de la identidad. Genera su clave, solicita credenciales y las presenta. | App Android |
| **Issuer** | Emite credenciales verificables firmadas. Registra e invalida DIDs. | Backend Java |
| **Verifier** | Verifica presentaciones del holder: valida firmas, estado del DID y revocación de VCs. | Backend Java |

> **POC:** Issuer y Verifier están implementados en el **mismo backend**, separados por endpoints distintos. En un sistema real serían organizaciones independientes.

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

### 2. Registro del DID en el issuer (primer uso)

Antes de solicitar credenciales, el holder registra su DID junto a su identificador de cliente. Esto permite al issuer conocer qué DIDs están activos y a qué persona corresponden.

```
Holder (app)              Issuer (backend)
─────────────────         ──────────────────────────────────

POST /dids/register ────────────────────────────→
{ client_id: "user@example.com",
  did: "did:key:zQ3s..." }
                          Guarda { did, clientId, active: true }
                          ←────────────── { did, client_id, active: true }
```

> Si el holder pierde el dispositivo, el issuer puede invalidar ese DID con
> `POST /dids/{did}/invalidate`. En el dispositivo nuevo se registra un DID nuevo
> bajo el mismo `client_id`.

### 3. Obtención de una Verifiable Credential

El DID debe estar registrado y activo para obtener un nonce.

```
Holder (app)              Issuer (backend)
─────────────────         ──────────────────────────────────
                          Tiene su propio DID e identidad

1. GET /credentials/nonce?holder_did=did:key:z... ──→
                          ✓ DID registrado y activo
                          ←────────────── { nonce }

2. Firma un Proof JWT con su clave privada
   (incluye el nonce y los claims que quiere acreditar)

3. POST /credentials/issue ─────────────────────→
   { holder_did, proof }
                          ✓ DID activo
                          ✓ Firma válida + nonce válido
                          Emite VC JWT firmada con su clave
                          Guarda solo metadatos (no el JWT)
                          ←────────────── { credential: <VC JWT> }

4. Almacena la VC cifrada en el dispositivo
```

### 3. Presentación y verificación de una VC

El holder construye una VP JWT (Verifiable Presentation) que empaqueta sus VCs y la firma con su clave privada. La envía al endpoint `POST /credentials/verify` del backend.

> **POC:** el endpoint de verificación está en el mismo backend que el issuer. En producción, el Verifier sería una organización separada que consumiría los endpoints públicos del issuer (`GET /dids/{did}` y `GET /credentials?holder_did=`) para consultar estado y revocación.

```
Holder (app)              Backend (Verifier + Issuer — mismo proceso)
─────────────────         ──────────────────────────────────────────

1. Toma sus VCs almacenadas
2. Construye VP JWT:
   { iss: holderDid,
     aud: "http://backend",
     vp: { verifiableCredential: [VC_JWT] } }
3. La firma con su clave privada

4. POST /credentials/verify ─────────────────→
   { "vp_jwt": "eyJ..." }

                          ✓ alg = ES256K
                          ✓ VP no expirada
                          ✓ Firma ES256K del holder válida
                            (clave pública derivada del did:key del iss)
                          ✓ DID del holder registrado y activo

                          Para cada VC dentro del VP:
                          ✓ Firma ES256K del issuer válida
                          ✓ iss de la VC = DID de este issuer
                          ✓ sub de la VC = holderDid del VP
                          ✓ VC no expirada
                          ✓ VC no revocada (consulta interna al store)

   ←─── 200 { "valid": true, "holder_did": "...",
               "credentials": [{ credential_id, type, subject, ... }] }
```

**Por qué no se necesita red para verificar firmas:** el `did:key` contiene la clave pública — se deriva directamente del DID sin llamadas externas. Solo el estado dinámico (DID activo, VC revocada) requiere consulta al store.

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

### Paso 2 — Registrar el DID en el issuer (una sola vez)

```
POST /dids/register
{ "client_id": "user@example.com", "did": "did:key:z..." }
→ { "did": "...", "client_id": "...", "active": true }
```

El issuer solo entrega nonces a DIDs que conoce y están activos.

### Paso 3 — Pedir el nonce (el DID debe estar activo)

```
GET /credentials/nonce?holder_did=did:key:z...
→ { "nonce": "abc123" }
```

El nonce es de un solo uso y tiene expiración de 5 minutos. Protege contra replay attacks: si alguien intercepta el Proof JWT, no puede reutilizarlo porque el nonce ya fue consumido.

### Paso 4 — Firmar el Proof JWT (con la clave privada)

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

### Paso 5 — El backend verifica sin conocer la clave privada

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

2. POST /dids/register ────────────────────────────────────────────▶
   { client_id: "user@example.com", did: "did:key:z..." }
                                       guarda { did, clientId, active: true }
   ◀─ { active: true } ───────────────────────────────────────────

3. GET /credentials/nonce?holder_did=did:key:z... ─────────────────▶
                                       ✓ DID registrado y activo
                                       guarda nonce de un solo uso
   ◀─ { nonce: "abc123" } ────────────────────────────────────────

4. ./scripts/gen-proof.sh <nonce>
   → lee clave privada de .holder-keys
   → firma JWT con nonce + subject_claims
   → imprime PROOF_JWT

5. POST /credentials/issue ────────────────────────────────────────▶
   { holder_did, proof: PROOF_JWT }
                                       ✓ DID activo
                                       ✓ firma válida (clave pública del DID)
                                       ✓ nonce consumido (one-time)
                                       emite VC JWT firmada por el issuer
   ◀─ { credential: "eyJ..." } ───────────────────────────────────
```

---

## Cómo funciona el VP JWT

El VP JWT (Verifiable Presentation) es el mecanismo con el que el holder demuestra ante el Verifier que posee una o más VCs. Lo construye él mismo, lo firma con su clave privada y lo envía al backend.

### Qué contiene

```
header  = { alg: ES256K, typ: JWT, kid: "did:key:z...#z..." }
payload = {
    iss: "did:key:z..."           ← DID del holder (quién presenta)
    aud: "https://backend"        ← a quién va dirigido
    iat: <unix timestamp>
    exp: <unix timestamp + 300>   ← válido 5 minutos
    vp: {
        type: ["VerifiablePresentation"],
        verifiableCredential: [   ← lista de VCs JWT a presentar
            "eyJ...VC_JWT..."
        ]
    }
}
firma = ECDSA(SHA256(header.payload), clave_privada_del_holder)
```

La diferencia clave con el Proof JWT: **no lleva nonce del issuer**. La VP se construye de forma autónoma — el holder decide cuándo y qué presenta.

### Qué verifica el backend al recibirlo

```
1. alg = ES256K
2. exp > ahora (presentación no expirada)
3. Derivar clave pública del holder desde el campo iss (did:key)
4. Verificar firma ES256K sobre header.payload
5. DID del holder está registrado y activo (consulta al store)

Por cada VC en vp.verifiableCredential:
6. Verificar firma ES256K del issuer (clave pública derivada del iss de la VC)
7. iss de la VC == DID de este issuer (rechaza VCs de issuers desconocidos)
8. sub de la VC == iss del VP (la VC le pertenece al holder que presenta)
9. exp de la VC > ahora
10. VC no revocada (consulta al store por su jti)
```

### Flujo completo de verificación

```
App Android / Terminal                 Backend (Verifier)
──────────────────────                 ──────────────────────────────
(ya tienes .holder-keys y VC_JWT)

./scripts/gen-vp.sh <VC_JWT>
  → lee clave privada de .holder-keys
  → construye VP JWT con la VC
  → firma con ES256K
  → POST /credentials/verify ────────────────────────────────────▶
                                        ✓ firma del holder
                                        ✓ DID activo
                                        ✓ firma del issuer en la VC
                                        ✓ VC no revocada
    ◀── { valid: true, holder_did, credentials: [...] } ─────────
```

---

### Para pruebas con Postman

```bash
# Paso 1 — generar identidad del holder (una sola vez)
./scripts/gen-holder.sh
# → imprime HOLDER_DID, guarda .holder-keys
# → copiar HOLDER_DID a la variable HOLDER_DID de la colección

# Paso 2 — registrar DID en el issuer (ejecutar "POST Register DID" en Postman)
# → body: { "client_id": "user@example.com", "did": "{{HOLDER_DID}}" }

# Paso 3 — obtener nonce (ejecutar "GET Nonce" en Postman)
# → NONCE se guarda automáticamente en la variable de colección

# Paso 4 — generar proof con el nonce
./scripts/gen-proof.sh <NONCE>
# → imprime PROOF_JWT
# → copiar a la variable PROOF_JWT de la colección

# Paso 5 — ejecutar "POST Issue VC" en Postman
# → guarda el valor de "credential" (VC_JWT)

# Paso 6 — generar y enviar la VP (ejecuta curl automáticamente)
./scripts/gen-vp.sh <VC_JWT>
# → imprime VP_JWT y resultado del backend (valid: true/false)
# → para probarlo en Postman: copiar VP_JWT a "POST Verify VP"
```

Ver [README-postman.md](README-postman.md) para la guía completa paso a paso.

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
  "aud": "https://backend.example.com",
  "iat": 1772674193,
  "exp": 1772674493,
  "vp": {
    "type": ["VerifiablePresentation"],
    "verifiableCredential": ["<VC_JWT>"]
  }
}
```
*Firmado con la clave privada del holder (ES256K). Válido 5 minutos. Sin nonce — el holder lo construye de forma autónoma.*

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
5. **Registro de DIDs** — saber qué DIDs están activos y a qué cliente pertenecen
6. **Persistencia** — solo metadatos de auditoría (H2 en local, Azure Table Storage en cloud)

No se necesita blockchain, PKI propia ni registro DID externo. `did:key` se resuelve localmente a partir del propio DID.

---

## Endpoints del backend

### Gestión de DIDs del holder

| Método | URL | Descripción |
|--------|-----|-------------|
| `POST` | `/dids/register` | Registra un DID asociado a un `client_id` |
| `GET`  | `/dids/{did}` | Estado del DID (activo/inactivo) — para verificadores |
| `POST` | `/dids/{did}/invalidate` | Invalida un DID (dispositivo perdido o app borrada) |
| `GET`  | `/clients/{clientId}/dids` | Lista todos los DIDs de un cliente |

### Credenciales

| Método | URL | Descripción |
|--------|-----|-------------|
| `GET`  | `/credentials/nonce?holder_did=` | Nonce de un solo uso (DID debe estar activo) |
| `POST` | `/credentials/issue` | Recibe proof JWT, emite VC JWT |
| `POST` | `/credentials/verify` | Verifica una VP JWT (firma, DID activo, VCs no revocadas) |
| `GET`  | `/credentials?holder_did=` | Metadatos de VCs emitidas (sin contenido) |
| `POST` | `/credentials/{credentialId}/revoke` | Revoca una VC por su ID |

### Identidad del issuer

| Método | URL | Descripción |
|--------|-----|-------------|
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
    ├── controller/
    │   ├── CredentialController.java     ← nonce, issue, revoke, metadatos
    │   └── HolderDIDController.java      ← register, invalidate, status
    ├── service/
    │   ├── NonceService.java             ← nonces single-use
    │   ├── ProofVerifier.java            ← verifica proof JWT
    │   ├── CredentialIssuerService.java  ← emite y firma VCs
    │   └── HolderDIDService.java         ← registro e invalidación de DIDs
    ├── store/
    │   ├── CredentialStore.java          ← interface
    │   ├── JpaCredentialStore.java       ← H2 (local)
    │   ├── AzureTableCredentialStore.java← Azure Table Storage
    │   ├── HolderDIDStore.java           ← interface
    │   ├── JpaHolderDIDStore.java        ← H2 (local)
    │   └── AzureTableHolderDIDStore.java ← Azure Table Storage
    └── model/
        ├── CredentialRecord.java         ← metadatos de VC emitida
        └── HolderDIDRecord.java          ← registro de DID del holder
```
