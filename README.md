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
