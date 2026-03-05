# Guía cripto para el dev mobile

Esta guía explica la lógica que la app Android debe implementar en relación al protocolo DID/VC: cómo generar claves, derivar el DID, armar y firmar el Proof JWT, y verificar la VC que devuelve el issuer. Los scripts `gen-holder.sh` y `gen-proof.sh` son una reimplementación Python del mismo proceso, útil para pruebas desde terminal o Postman.

---

## Índice

1. [Curva secp256k1 — el punto de partida](#1-curva-secp256k1)
2. [Generación del par de claves](#2-generación-del-par-de-claves)
3. [Compresión de la clave pública](#3-compresión-de-la-clave-pública)
4. [Derivación del DID](#4-derivación-del-did)
5. [Proof JWT y VC JWT — estructura, firma y validación](#5-proof-jwt-y-vc-jwt--estructura-firma-y-validación)
6. [Verificación de la VC al recibirla](#6-verificación-de-la-vc-al-recibirla)
7. [Protección de la clave privada en Android](#7-protección-en-android)
8. [Equivalencia Android ↔ Python](#8-equivalencia-android--python)

---

## 1. Curva secp256k1

Toda la criptografía se basa en la curva elíptica **secp256k1** — la misma que usa Bitcoin y Ethereum.

Una curva elíptica es un conjunto de puntos que satisfacen la ecuación:

```
y² = x³ + 7  (mod p)
```

donde `p` es un número primo de 256 bits definido en el estándar secp256k1.

**Por qué secp256k1 y no RSA o AES:**
- Las claves son pequeñas (32 bytes privada, 33 bytes pública comprimida)
- La firma es compacta (64 bytes)
- Es el estándar de facto en el ecosistema DID/VC
- RFC 8812 la formaliza como `ES256K` para uso en JWT

---

## 2. Generación del par de claves

### Qué es

- **Clave privada**: un número entero aleatorio de 256 bits (32 bytes). Solo existe en el dispositivo.
- **Clave pública**: un punto (x, y) en la curva, derivado de la privada. Es pública — se puede compartir libremente.

La relación es unidireccional: de la privada se deriva la pública, pero no al revés (problema del logaritmo discreto).

### En Android — `KeyManager.java`

```java
// Genera un par secp256k1 usando BouncyCastle
ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", "BC");
kpg.initialize(spec, new SecureRandom());
KeyPair pair = kpg.generateKeyPair();

ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();
ECPublicKey  pub  = (ECPublicKey)  pair.getPublic();

// Clave privada: el escalar S (entero de 256 bits)
byte[] privBytes = to32Bytes(priv.getS());

// Clave pública: el punto (x, y) comprimido a 33 bytes
byte[] pubBytes  = compressedPublicKey(pub);
```

### En Python — `gen-holder.sh`

```python
from cryptography.hazmat.primitives.asymmetric.ec import generate_private_key, SECP256K1
from cryptography.hazmat.backends import default_backend

priv_key  = generate_private_key(SECP256K1(), default_backend())
pub_key   = priv_key.public_key()
```

---

## 3. Compresión de la clave pública

### Qué es y para qué sirve

La clave pública es simplemente un punto en la curva, definido por dos coordenadas: `(x, y)`. Cada coordenada ocupa 32 bytes, así que en su forma completa la clave pública ocupa 64 bytes.

El problema es que esos 64 bytes tienen que viajar dentro del DID — que es un string que la app muestra, copia, comparte y manda en requests HTTP. Cuanto más corto, mejor.

**La compresión reduce la clave pública de 64 a 33 bytes**, sin perder información. Esos 33 bytes son lo que se usa para construir el DID y para incluir en el `kid` del JWT.

### Cómo funciona (sin entrar en matemática)

Dado un valor de `x`, la curva solo puede producir dos posibles valores de `y`. Los dos siempre tienen paridades opuestas: uno es par y el otro es impar.

Entonces en vez de guardar `(x, y)` completo, alcanza con guardar:

```
[prefijo] [x — 32 bytes]
    1 byte      32 bytes
  ─────────────────────
        33 bytes total

prefijo 0x02 → el y que le corresponde es par
prefijo 0x03 → el y que le corresponde es impar
```

Cualquier librería criptográfica puede reconstruir `y` a partir de `x` y el prefijo cuando lo necesite. La app nunca tiene que hacer eso manualmente — solo trabaja con los 33 bytes comprimidos.

### En el código

En Android, la compresión se hace una sola vez al generar las claves y el resultado se guarda en `SharedPreferences` como hex. Después, `DIDManager` lo lee directamente para construir el DID.

```java
// KeyManager.java — al generar el par de claves
byte[] pubBytes = compressedPublicKey(pub);  // → 33 bytes
prefs().edit().putString("public_key_hex", hex(pubBytes)).apply();
```

```java
// DIDManager.java — para construir el DID usa esos 33 bytes directamente
byte[] pubKey = KeyManager.unhex(keyManager.getPublicKeyHex());  // 33 bytes
```

En Python hace lo mismo en una línea:

```python
pub_bytes = pub_key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint)
# → 33 bytes: [0x02 o 0x03] + x
```

---

## 4. Derivación del DID

El DID se deriva **determinísticamente** de la clave pública comprimida. No hay registro externo — el DID se puede recalcular siempre que tengas la clave pública.

### Algoritmo did:key para secp256k1

```
1. Tomar los 33 bytes de la clave pública comprimida

2. Prepender el identificador multicodec de secp256k1-pub:
   bytes [0xe7, 0x01]  ← varint encoding del código 0xe7 (secp256k1-pub)
   resultado: 35 bytes

3. Codificar en Base58btc (alfabeto Bitcoin)
   resultado: string de ~48 caracteres

4. Prepender 'z' (prefijo multibase para base58btc)
   resultado: "z" + base58btc(...)

5. Construir el DID:
   "did:key:" + resultado
```

### En Android — `DIDManager.java`

```java
private static final byte[] SECP256K1_MULTICODEC = {(byte) 0xe7, (byte) 0x01};

public String getDID() {
    byte[] pubKey   = KeyManager.unhex(keyManager.getPublicKeyHex());
    byte[] multikey = concat(SECP256K1_MULTICODEC, pubKey);  // [0xe7, 0x01] + pubKey (35 bytes)
    String encoded  = "z" + Base58.encode(multikey);          // multibase base58btc
    return "did:key:" + encoded;
}
```

### En Python — `gen-holder.sh`

```python
ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

def b58encode(data: bytes) -> str:
    n = int.from_bytes(data, "big")
    result = []
    while n > 0:
        n, r = divmod(n, 58)
        result.append(ALPHABET[r:r+1])
    leading = len(data) - len(data.lstrip(b"\x00"))
    return (ALPHABET[0:1] * leading + b"".join(reversed(result))).decode()

multicodec = bytes([0xe7, 0x01]) + pub_bytes   # mismo que Java
encoded    = "z" + b58encode(multicodec)
holder_did = "did:key:" + encoded
```

### Por qué Base58 (y no Base64)

Base58 es Base64 sin los caracteres visualmente ambiguos: `0` (cero), `O` (letra O), `I` (letra I), `l` (letra l), y sin `+` ni `/`. El resultado es más fácil de copiar y comparar visualmente.

### El key ID (kid)

Para el header del JWT se necesita el `kid`, que identifica qué clave dentro del DID se usó para firmar:

```
did:key:zQ3sh...    →    kid = did:key:zQ3sh...#zQ3sh...
                                               ↑
                               fragment = la parte codificada del DID
```

```java
// DIDManager.java
public String getKeyId(String did) {
    String fragment = did.substring("did:key:".length()); // quita "did:key:"
    return did + "#" + fragment;
}
```

---

## 5. Proof JWT y VC JWT — estructura, firma y validación

---

### Estándares

| Estándar | Qué define |
|----------|------------|
| **RFC 7519** — JWT | El formato compacto `header.payload.signature` en base64url |
| **RFC 8812** — ES256K | El uso de ECDSA secp256k1 + SHA-256 como algoritmo de firma en JWT |
| **RFC 6979** | Generación determinística del nonce interno de firma (evita vulnerabilidades por aleatoriedad) |
| **OpenID4VCI (draft)** | El tipo `openid4vci-proof+jwt` y los campos del payload del Proof JWT |
| **W3C VC Data Model 1.1** | La estructura del objeto `vc` dentro del payload de la VC JWT |
| **W3C DID Core 1.0** | El formato del DID y cómo se referencia la clave en el `kid` |

---

### Formato base de un JWT

```
base64url(header_json)  .  base64url(payload_json)  .  base64url(firma)
──────────────────────     ────────────────────────     ──────────────────
     HEADER                      PAYLOAD                   SIGNATURE
```

La firma cubre **solo** los dos primeros segmentos concatenados con punto. El tercer segmento es la firma de esos dos.

---

## Proof JWT

Lo genera el holder y lo envía al issuer como prueba de que controla su clave privada.

### Cómo se arma

**Header** (`CredentialRequestBuilder.java` / `gen-proof.sh`):

```json
{
  "alg": "ES256K",
  "typ": "openid4vci-proof+jwt",
  "kid": "did:key:zQ3sh...#zQ3sh..."
}
```

| Campo | Valor | Propósito |
|-------|-------|-----------|
| `alg` | `ES256K` | Indica con qué algoritmo se firmó. El backend rechaza si no es ES256K. |
| `typ` | `openid4vci-proof+jwt` | Identifica el propósito del token. Evita que un JWT de otro uso sea aceptado aquí. |
| `kid` | `did#fragment` | Apunta a la clave pública del holder. El backend la usa para verificar la firma. |

**Payload** (`CredentialRequestBuilder.java` / `gen-proof.sh`):

```json
{
  "iss": "did:key:zQ3sh...",
  "aud": "https://issuer.example.com",
  "iat": 1772674193,
  "exp": 1772674493,
  "nonce": "abc123...",
  "credential_type": "UniversityDegreeCredential",
  "subject_claims": {
    "givenName": "Juan",
    "familyName": "Pérez",
    "email": "juan@example.com"
  }
}
```

| Campo | Quién lo pone | Propósito |
|-------|---------------|-----------|
| `iss` | Holder (su DID) | Identifica quién firma. De aquí se extrae la clave pública para verificar. |
| `aud` | Holder (URL del issuer) | Ata el JWT a un destinatario. Otro issuer no puede reutilizarlo. |
| `iat` / `exp` | Holder (timestamps actuales) | Ventana de validez de 5 minutos. Un JWT interceptado después expira. |
| `nonce` | Holder (lo recibió del backend) | Valor único por solicitud. El backend lo verifica y lo consume. Impide replay. |
| `credential_type` | Holder | Qué tipo de credencial pide. |
| `subject_claims` | Holder | Los datos que quiere que figuren en la VC. El holder decide qué declara. |

### Con qué se firma

Con la **clave privada secp256k1 del holder** (`JWTUtil.java`):

```
signing_input = base64url(header) + "." + base64url(payload)
firma         = ES256K(signing_input, clave_privada_holder)
proof_jwt     = signing_input + "." + base64url(firma)
```

La clave privada del holder nunca sale del dispositivo. Solo la firma viaja en el JWT.

### Cómo lo valida el backend

`ProofVerifier.java` — en orden:

```
1. Verificar que tiene 3 partes separadas por "."
2. alg == "ES256K"                              → si no, rechazar
3. iss == holder_did enviado en el body          → si no, rechazar
4. exp > ahora                                  → si expiró, rechazar
5. nonce existe en el registro del backend       → si no, rechazar
   y no fue usado antes                          → si ya se usó, rechazar
   → marcar como consumido
6. Extraer clave pública del holderDid:
   did:key:z<encoded> → base58decode → quitar [0xe7,0x01] → 33 bytes
7. Verificar la firma ES256K con esa clave pública
   → si inválida, rechazar
8. Si todo ok → pasar subject_claims al servicio de emisión
```

El backend **nunca necesita la clave privada del holder** — solo la clave pública, que está embebida en el DID.

---

## VC JWT

Lo genera el issuer y lo entrega al holder. Es la credencial verificable.

### Cómo se arma

**Header** (`CredentialIssuerService.java`):

```json
{
  "alg": "ES256K",
  "typ": "JWT",
  "kid": "did:key:zIssuer...#zIssuer..."
}
```

Aquí el `kid` apunta a la clave del **issuer**, no del holder.

**Payload** (`CredentialIssuerService.java`):

```json
{
  "jti": "urn:uuid:550e8400-e29b-41d4-a716-446655440000",
  "iss": "did:key:zIssuer...",
  "sub": "did:key:zHolder...",
  "iat": 1772674193,
  "exp": 1772760593,
  "vc": {
    "@context": ["https://www.w3.org/2018/credentials/v1"],
    "id": "urn:uuid:550e8400-...",
    "type": ["VerifiableCredential", "UniversityDegreeCredential"],
    "issuer": "did:key:zIssuer...",
    "issuanceDate": "2026-03-05T10:00:00Z",
    "expirationDate": "2027-03-05T10:00:00Z",
    "credentialSubject": {
      "id": "did:key:zHolder...",
      "givenName": "Juan",
      "familyName": "Pérez",
      "email": "juan@example.com"
    }
  }
}
```

| Campo | Quién lo pone | Origen del dato | Propósito |
|-------|---------------|-----------------|-----------|
| `jti` | Issuer | UUID generado | ID único de la VC. Permite revocarla. |
| `iss` | Issuer | Su propio DID | Identifica quién emitió la VC. El verifier lo usa para verificar la firma. |
| `sub` | Issuer | `holder_did` del request | A quién pertenece la VC. |
| `iat` / `exp` | Issuer | Configurable (`vc-ttl-seconds`, default 24h) | Vigencia de la credencial. |
| `vc.credentialSubject` | Issuer | `subject_claims` del Proof JWT | Los datos del holder que el issuer certifica. |

El issuer toma los `subject_claims` del Proof JWT (que el holder declaró) y los certifica firmándolos con su propia clave.

### Con qué se firma

Con la **clave privada secp256k1 del issuer** (`CredentialIssuerService.java`):

```
signing_input = base64url(header) + "." + base64url(payload)
firma         = ES256K(signing_input, clave_privada_issuer)
vc_jwt        = signing_input + "." + base64url(firma)
```

La clave privada del issuer vive en Azure Key Vault y se carga al arrancar el servicio.

### Qué guarda el backend (y qué no)

```
Guarda  → hash SHA-256 del VC JWT  (para poder revocar)
         → metadatos: holderDid, credentialType, issuedAt, expiresAt, revoked

No guarda → el JWT completo
```

El VC JWT se entrega **una sola vez** al holder. El holder es el único custodio. Si lo pierde, no hay forma de recuperarlo — habría que emitir uno nuevo.

### Cómo lo valida el verifier

Un verifier externo que recibe el VC JWT de un holder:

```
1. Separar las 3 partes del JWT
2. Decodificar el header → extraer kid (DID del issuer)
3. Extraer clave pública del issuer desde su DID:
   did:key:z<encoded> → base58decode → quitar [0xe7,0x01] → 33 bytes
4. Verificar la firma ES256K con esa clave pública
5. Verificar exp > ahora
6. Verificar que sub (holderDid) coincide con quien presenta la VC
7. Opcional: consultar /credentials?holder_did= para verificar que no está revocada
```

El verifier no necesita contactar al issuer para verificar la firma — la clave pública del issuer se puede resolver directamente desde su DID.

---

## Flujo completo end-to-end

```
HOLDER                          ISSUER                        VERIFIER
──────                          ──────                        ────────
Tiene: clave privada
       clave pública
       DID (del DID)

GET /nonce ─────────────────────▶
                                 genera nonce
                                 guarda (nonce → holderDid, TTL 5min)
◀─ { nonce } ───────────────────

Arma Proof JWT:
  header { alg, typ, kid }
  payload { iss=DID, aud=URL,
            nonce, subject_claims }
  firma con clave privada

POST /issue ────────────────────▶
  { holder_did, proof: JWT }
                                 verifica Proof JWT:
                                   alg, iss, exp, nonce, firma
                                 consume el nonce
                                 arma VC JWT:
                                   header { kid=issuerDID }
                                   payload { iss=issuerDID,
                                             sub=holderDID,
                                             vc { credentialSubject } }
                                   firma con clave privada del issuer
                                 guarda solo hash + metadatos
◀─ { credential: VC_JWT } ──────

Guarda VC_JWT en dispositivo
(cifrado, nunca en claro)

                                               Presenta VC_JWT ─────────▶
                                                                          verifica firma
                                                                          con clave pública
                                                                          del issuer (del DID)
                                                                          verifica exp, sub
                                                                ◀─ ok ───
```

---

## Diferencias clave entre Proof JWT y VC JWT

| | Proof JWT | VC JWT |
|--|-----------|--------|
| **Lo genera** | Holder | Issuer |
| **Lo firma** | Clave privada del holder | Clave privada del issuer |
| **Se verifica con** | Clave pública del holder (del DID) | Clave pública del issuer (del DID) |
| **`iss`** | DID del holder | DID del issuer |
| **`aud` / `sub`** | `aud` = URL del issuer | `sub` = DID del holder |
| **`typ`** | `openid4vci-proof+jwt` | `JWT` |
| **Vida útil** | 5 minutos | 24 horas (configurable) |
| **Propósito** | Demostrar posesión de clave para pedir una VC | Credencial verificable emitida por el issuer |
| **Nonce** | Sí (anti-replay) | No |
| **Se guarda** | No (descartable tras verificar) | Sí, en el dispositivo del holder |

---

## 6. Verificación de la VC al recibirla

Cuando el holder recibe el VC JWT del issuer, **debe verificarlo antes de guardarlo**. Actualmente `CredentialService.java` lo guarda directamente sin validar — esta sección describe la lógica que falta implementar.

### Por qué es necesario verificar

La app configura un `ISSUER_URL` al arrancar. Pero sin verificar la firma, cualquier servidor que responda en esa URL podría devolver un JWT firmado con su propia clave y la app lo guardaría como legítimo. La verificación criptográfica es la garantía que no depende de la red.

### Qué verificar, en orden

```
JWT recibido: eyJhbGci...  .  eyJpc3Mi...  .  firma
                 header          payload
```

**1. Estructura válida**
El JWT debe tener exactamente 3 partes separadas por `.`.

**2. Algoritmo esperado**
```
header.alg == "ES256K"
```
Si el issuer devuelve un algoritmo distinto, rechazar. Evita ataques de confusión de algoritmo (ej. `alg: none`).

**3. La VC es para este holder**
```
payload.sub == mi propio holderDid
```
Garantiza que no se está guardando accidentalmente la credencial de otro usuario.

**4. La VC no está expirada**
```
payload.exp > ahora
```
No tiene sentido guardar una VC que ya venció al recibirla.

**5. El issuer es el esperado**
```
payload.iss == issuerDid conocido por la app
```
Este es el campo más crítico junto con la firma. El `issuerDid` puede obtenerse de dos formas:
- Hardcodeado como constante en la app (más simple, válido para un issuer fijo)
- Consultado al arrancar con `GET /issuer/did` y guardado en memoria

**6. La firma es válida**
Verificar que el JWT fue firmado por la clave privada correspondiente al `iss`.

Como el issuer usa `did:key`, la clave pública está embebida en el propio DID — no hace falta llamar a ningún endpoint para resolverla:

```
payload.iss = "did:key:zIssuer..."
                        ↓
              quitar prefijo "did:key:"
                        ↓
              quitar prefijo multibase "z"
                        ↓
              base58btc decode
                        ↓
              quitar primeros 2 bytes [0xe7, 0x01]
                        ↓
              33 bytes = clave pública comprimida del issuer
                        ↓
              verificar firma ES256K del JWT con esa clave pública
```

La misma lógica que usa el backend para resolver el DID del holder aplica acá para resolver el DID del issuer. El código ya existe en el backend en `DIDKeyUtil.publicKeyFromDIDKey()` — el dev mobile necesita el equivalente en Java/Android con BouncyCastle (que ya es una dependencia del proyecto).

### Flujo completo en `CredentialService.requestCredential()`

```
POST /credentials/issue
        ↓
recibir { credential: "eyJ..." }
        ↓
┌─ verificar ────────────────────────────────────┐
│  1. estructura: 3 partes                        │
│  2. header.alg == "ES256K"                      │
│  3. payload.sub == holderDid                    │
│  4. payload.exp > ahora                         │
│  5. payload.iss == issuerDid esperado           │
│  6. firma válida (clave pública extraída del iss)│
└────────────────────────────────────────────────┘
        ↓ ok          ↓ falla
  storeCredential   lanzar excepción
```

### Estado actual del código

`CredentialService.java` línea 64 hace directamente:
```java
storeCredential(vcJwt);  // ← falta la verificación antes de esta línea
```

La verificación de firma requiere agregar un método equivalente a `DIDKeyUtil.verifyES256K()` accesible desde Android, usando BouncyCastle que ya está en el proyecto como dependencia de `KeyManager.java`.

---

## 7. Protección de la clave privada en Android

Esta parte **no existe** en los scripts Python — es exclusiva del dispositivo Android. Es la capa que hace que la clave privada nunca salga del chip de seguridad.

### Jerarquía de protección

```
Clave privada secp256k1 (32 bytes)
        │
        │  cifrada con AES-256/GCM
        ▼
Clave privada cifrada  →  guardada en SharedPreferences
        ↑
        │  la clave AES vive en:
        │
        ├── StrongBox (API 28+)  ←  chip dedicado (Titan M, Pixel 3+)
        │   Mayor aislamiento: opera independientemente del SoC principal
        │
        ├── TEE (Trusted Execution Environment)  ←  casi todos los Android modernos
        │   Zona aislada dentro del SoC principal. Hardware-backed.
        │
        └── Software Keystore  ←  último recurso (emuladores, dispositivos viejos)
            Sin respaldo hardware. La clave AES en RAM del proceso.
```

### Flujo al generar las claves — `KeyManager.java`

```java
// 1. Crear (o recuperar) la clave AES de wrapping en el Keystore
SecretKey wrapKey = getOrCreateWrapKey();
// → intenta StrongBox primero, cae a TEE si no está disponible

// 2. Generar par secp256k1 (BouncyCastle, en RAM)
KeyPair pair = kpg.generateKeyPair();
byte[] privBytes = to32Bytes(priv.getS());  // 32 bytes en RAM

// 3. Cifrar la clave privada con AES/GCM
Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
enc.init(Cipher.ENCRYPT_MODE, wrapKey);     // la clave AES nunca sale del hardware
byte[] iv      = enc.getIV();
byte[] encPriv = enc.doFinal(privBytes);    // privBytes cifrado

// 4. Persistir el cifrado (nunca la clave en claro)
prefs().edit()
    .putString("enc_private_key", base64(encPriv))
    .putString("aes_gcm_iv",      base64(iv))
    .putString("public_key_hex",  hex(pubBytes))
    .apply();

// privBytes debería limpiarse de RAM aquí (Arrays.fill con 0)
```

### Flujo al firmar — `KeyManager.java` + `JWTUtil.java`

```java
// Descifrar la clave privada (toca RAM brevemente)
byte[] privBytes = keyManager.loadPrivateKey();
// → el Keystore descifra usando la clave AES que nunca salió del hardware

// Firmar (BouncyCastle opera en RAM con los bytes)
String jwt = JWTUtil.sign(headerJson, payloadJson, privBytes);

// privBytes debería limpiarse de RAM inmediatamente después
```

### AES-GCM — por qué este modo

AES-GCM (Galois/Counter Mode) provee **cifrado autenticado**: no solo cifra, también genera un tag de autenticación de 128 bits. Si alguien modifica el cifrado en disco, el descifrado falla antes de devolver bytes.

- **IV (Initialization Vector)**: 12 bytes aleatorios generados por el sistema. Nunca se reutiliza con la misma clave.
- **Tag**: 128 bits al final del ciphertext. Detecta manipulación.

---

## 8. Equivalencia Android ↔ Python

| Paso | Android | Python (gen-*.sh) | Resultado |
|------|---------|-------------------|-----------|
| Generar clave privada | `KeyPairGenerator` (BouncyCastle) | `generate_private_key(SECP256K1())` | 32 bytes aleatorios |
| Clave pública comprimida | `compressedPublicKey()` manual | `PublicFormat.CompressedPoint` | 33 bytes `[0x02/0x03] + x` |
| Derivar DID | `DIDManager.getDID()` | `b58encode([0xe7,0x01] + pub_bytes)` | `did:key:zQ3sh...` |
| Persistir clave privada | `SharedPreferences` + AES-GCM (Keystore) | `.holder-keys` (texto plano) | Solo en el dispositivo |
| Firmar JWT | `JWTUtil.sign()` (BouncyCastle RFC6979) | `priv_key.sign(ECDSA(SHA256()))` | JWT ES256K compacto |
| Formato firma | `R‖S` (64 bytes) manual | `decode_dss_signature` + `r.to_bytes + s.to_bytes` | Idéntico |
| Codificación | `Base64Url.java` propio | `base64.urlsafe_b64encode(...).rstrip(b"=")` | Idéntico |

### Diferencia principal

Los scripts Python guardan la clave privada en `.holder-keys` **en texto plano** (hex). Es solo para desarrollo y pruebas. En Android, la clave privada **nunca existe en texto plano en disco** — siempre está cifrada con una clave que vive en hardware.

---

## Archivos relevantes

| Archivo | Responsabilidad |
|---------|-----------------|
| `android/.../security/KeyManager.java` | Genera, cifra y carga la clave privada secp256k1 |
| `android/.../did/DIDManager.java` | Deriva el DID y el key ID desde la clave pública |
| `android/.../credential/CredentialRequestBuilder.java` | Construye y firma el Proof JWT |
| `android/.../util/JWTUtil.java` | Implementa ES256K y el formato JWT compacto |
| `android/.../util/Base58.java` | Encoding Base58btc para el DID |
| `scripts/gen-holder.sh` | Equivalente Python de KeyManager + DIDManager |
| `scripts/gen-proof.sh` | Equivalente Python de CredentialRequestBuilder + JWTUtil |
