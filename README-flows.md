# Flujos del sistema

Diagramas de secuencia: dispositivo Android ↔ backend ↔ servicios Azure.

---

## 1. Generación de identidad del holder

Ocurre **una sola vez** en el dispositivo, sin red.

```mermaid
sequenceDiagram
    participant A as 📱 App Android

    Note over A: Sin red — todo ocurre localmente

    A->>A: 1. Generar clave privada secp256k1<br/>(32 bytes — SecureRandom)
    A->>A: 2. Derivar clave pública comprimida<br/>(33 bytes: [0x02/0x03] + coordenada X)
    A->>A: 3. Derivar DID<br/>base58btc([0xe7,0x01] + pubKey)<br/>→ "did:key:zQ3sh..."
    A->>A: 4. Proteger clave privada<br/>cifrar con AES-256/GCM<br/>clave AES vive en Android Keystore<br/>(StrongBox si disponible, sino TEE)
    A->>A: 5. Persistir en SharedPreferences<br/>enc_private_key, aes_gcm_iv, public_key_hex
```

> La clave privada nunca existe en claro en disco. El DID es la clave pública codificada — no requiere registro externo.

---

## 2. Emisión de Verifiable Credential

```mermaid
sequenceDiagram
    participant A as 📱 App Android (Holder)
    participant B as 🖥️ Backend (Issuer)
    participant KV as 🔐 Key Vault
    participant TS as 🗄️ Table Storage

    Note over A,B: Paso 1 — Obtener nonce

    A->>+B: GET /credentials/nonce?holder_did=did:key:zQ3sh...
    Note right of B: genera nonce aleatorio<br/>guarda { nonce → holderDid }<br/>TTL: 5 minutos
    B-->>-A: 200 OK<br/>{ "nonce": "a3f7c2..." }

    Note over A: Paso 2 — Construir Proof JWT (local)

    A->>A: header:<br/>{ alg: "ES256K",<br/>  typ: "openid4vci-proof+jwt",<br/>  kid: "did:key:zQ3sh...#zQ3sh..." }
    A->>A: payload:<br/>{ iss: "did:key:zQ3sh...",<br/>  aud: "https://issuer...",<br/>  iat: now, exp: now+300,<br/>  nonce: "a3f7c2...",<br/>  credential_type: "UniversityDegreeCredential",<br/>  subject_claims: { givenName, familyName, email } }
    A->>A: descifrar clave privada (Keystore)<br/>firmar base64url(header).base64url(payload)<br/>con ES256K → 64 bytes R‖S<br/>limpiar clave privada de RAM

    Note over A,TS: Paso 3 — Emitir la VC

    A->>+B: POST /credentials/issue<br/>{ "holder_did": "did:key:zQ3sh...",<br/>  "proof": "eyJhbGci...",<br/>  "credentialType": "UniversityDegreeCredential" }

    B->>B: ✓ alg == "ES256K"
    B->>B: ✓ iss == holder_did
    B->>B: ✓ exp > now
    B->>B: ✓ nonce existe y no fue usado → consumir
    B->>B: ✓ clave pública del DID:<br/>  did:key:z... → base58decode<br/>  → quitar [0xe7,0x01] → 33 bytes
    B->>B: ✓ verificar firma ES256K

    B->>+KV: obtener clave privada del issuer<br/>(secret: issuer-key-pem)
    KV-->>-B: PEM base64

    B->>B: header VC:<br/>{ alg: "ES256K", typ: "JWT",<br/>  kid: "did:key:zIssuer...#zIssuer..." }
    B->>B: payload VC:<br/>{ jti: "urn:uuid:...",<br/>  iss: "did:key:zIssuer...",<br/>  sub: "did:key:zQ3sh...",<br/>  iat: now, exp: now+86400,<br/>  vc: { @context, type, issuer,<br/>        credentialSubject: {<br/>          id: holderDid,<br/>          givenName, familyName, email<br/>        } } }
    B->>B: firmar con clave privada del issuer → VC JWT

    B->>+TS: guardar metadatos<br/>{ credentialId, holderDid, issuerDid,<br/>  credentialType, vcJwtHash,<br/>  issuedAt, expiresAt, revoked: false }
    TS-->>-B: ok

    B-->>-A: 200 OK<br/>{ "credential": "eyJhbGci..." }

    Note over A: Paso 4 — Verificar y guardar la VC ⚠️ pendiente

    A->>A: ✓ alg == "ES256K"
    A->>A: ✓ payload.sub == mi holderDid
    A->>A: ✓ payload.exp > now
    A->>A: ✓ payload.iss == issuerDid esperado
    A->>A: ✓ clave pública del iss (did:key → 33 bytes)<br/>  verificar firma ES256K
    A->>A: cifrar y guardar VC JWT<br/>EncryptedSharedPreferences (AES-256/GCM)
```

> ⚠️ El paso 4 (verificación en el dispositivo) está pendiente de implementar en `CredentialService.java`.
> El issuer guarda solo el hash SHA-256 del JWT — nunca el JWT completo. El holder es el único custodio.

---

## 3. Presentación ante un Verificador

```mermaid
sequenceDiagram
    participant V as 🔍 Verifier (externo)
    participant A as 📱 App Android (Holder)
    participant B as 🖥️ Backend (Issuer)

    V->>A: solicitar presentación<br/>{ nonce: "vrf9x...", requested_types: [...] }

    Note over A: el holder decide qué VCs compartir

    A->>A: leer VCs de EncryptedSharedPreferences
    A->>A: seleccionar VCs que satisfacen el request

    A->>A: header VP:<br/>{ alg: "ES256K", typ: "JWT",<br/>  kid: "did:key:zQ3sh...#zQ3sh..." }
    A->>A: payload VP:<br/>{ iss: "did:key:zQ3sh...",<br/>  aud: verifierDid,<br/>  iat: now, exp: now+300,<br/>  nonce: "vrf9x...",<br/>  vp: { type: ["VerifiablePresentation"],<br/>        verifiableCredential: ["eyJ...vc1"] } }
    A->>A: descifrar clave privada (Keystore)<br/>firmar VP con ES256K<br/>limpiar clave privada de RAM

    A->>V: VP JWT<br/>"eyJhbGci..."

    Note over V: verificar la VP

    V->>V: ✓ clave pública del holder (did:key del iss → 33 bytes)
    V->>V: ✓ firma ES256K del VP JWT
    V->>V: ✓ nonce coincide con el enviado
    V->>V: ✓ exp > now

    Note over V: verificar cada VC dentro del VP

    loop por cada VC en vp.verifiableCredential
        V->>V: ✓ clave pública del issuer (did:key del vc.iss → 33 bytes)
        V->>V: ✓ firma ES256K de la VC
        V->>V: ✓ vc.exp > now
        V->>V: ✓ vc.sub == holderDid del VP
    end

    opt verificar revocación (opcional pero recomendado)
        V->>+B: GET /credentials?holder_did=did:key:zQ3sh...
        B-->>-V: 200 OK<br/>[{ "credential_id": "urn:uuid:...",<br/>   "revoked": false,<br/>   "expires_at": "2027-..." }]
        V->>V: ✓ ninguna VC está revocada
    end

    V->>A: ✓ presentación válida
```

> El verifier resuelve la clave del issuer directamente desde su `did:key` — sin contactarlo.
> El holder controla qué VCs incluye en cada presentación.

---

## 4. Revocación de una VC

```mermaid
sequenceDiagram
    participant ADM as 👤 Administrador
    participant B as 🖥️ Backend (Issuer)
    participant TS as 🗄️ Table Storage

    ADM->>+B: POST /credentials/{credentialId}/revoke
    Note right of ADM: credentialId = "urn:uuid:550e8400-..."

    B->>+TS: buscar por credentialId
    TS-->>-B: { credentialId, holderDid, revoked: false }

    B->>TS: actualizar revoked = true
    TS-->>B: ok

    B-->>-ADM: 204 No Content
```

> El JWT en el dispositivo del holder no se modifica — la firma del issuer sigue siendo válida.
> El cambio está solo en el registro del backend. Los verifiers que consulten `/credentials` verán `revoked: true`.

---

## 5. Endpoints — referencia rápida

```mermaid
sequenceDiagram
    participant C as Cliente
    participant B as 🖥️ Backend

    C->>+B: GET /issuer/did
    B-->>-C: 200 { "did": "did:key:z...", "public_key_hex": "03..." }

    C->>+B: GET /issuer/did-document
    B-->>-C: 200 { "@context", "id", "verificationMethod", "authentication" }

    C->>+B: GET /credentials/nonce?holder_did=did:key:z...
    B-->>-C: 200 { "nonce": "a3f7..." }

    C->>+B: POST /credentials/issue<br/>{ holder_did, proof, credentialType }
    B-->>-C: 200 { "credential": "eyJ..." }

    C->>+B: GET /credentials?holder_did=did:key:z...
    B-->>-C: 200 [{ credential_id, credential_type,<br/>         issued_at, expires_at, revoked }]

    C->>+B: POST /credentials/{credentialId}/revoke
    B-->>-C: 204 No Content
```
