# Algoritmos criptográficos del holder

Esta guía explica paso a paso los algoritmos que usa la app Android para generar claves, derivar su DID y firmar el Proof JWT. Los scripts `gen-holder.sh` y `gen-proof.sh` son una reimplementación Python del mismo proceso, útil para pruebas desde terminal o Postman.

---

## Índice

1. [Curva secp256k1 — el punto de partida](#1-curva-secp256k1)
2. [Generación del par de claves](#2-generación-del-par-de-claves)
3. [Compresión de la clave pública](#3-compresión-de-la-clave-pública)
4. [Derivación del DID](#4-derivación-del-did)
5. [Firma ES256K — el Proof JWT](#5-firma-es256k)
6. [Protección de la clave privada en Android](#6-protección-en-android)
7. [Equivalencia Android ↔ Python](#7-equivalencia-android--python)

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

La clave pública es un punto (x, y) en la curva. Ambas coordenadas son enteros de 256 bits (32 bytes cada una), lo que daría 64 bytes. Pero se puede comprimir a 33 bytes.

### Por qué funciona la compresión

La curva tiene simetría: para cada `x`, hay exactamente dos valores de `y` posibles, y siempre son opuestos en paridad (uno par, uno impar). Por eso alcanza con guardar:

```
prefijo (1 byte) + x (32 bytes) = 33 bytes

prefijo:
  0x02  →  y es par
  0x03  →  y es impar
```

Quien necesite la clave pública completa puede reconstruir `y` desde `x` y el prefijo.

### En Android — `KeyManager.java`

```java
private static byte[] compressedPublicKey(ECPublicKey pub) {
    byte[] x = to32Bytes(pub.getW().getAffineX());
    byte[] y = to32Bytes(pub.getW().getAffineY());
    byte[] out = new byte[33];
    // Si y es impar → 0x03, si es par → 0x02
    out[0] = (byte) ((y[31] & 0x01) == 0 ? 0x02 : 0x03);
    System.arraycopy(x, 0, out, 1, 32);
    return out;
}
```

### En Python — `gen-holder.sh`

```python
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

# CompressedPoint hace exactamente lo mismo que el código Java de arriba
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

## 5. Firma ES256K

ES256K = **E**CDSA con curva **S**ecp256k1 y hash **256** (SHA-256). Es el algoritmo estándar para JWT con claves secp256k1 (RFC 8812).

### Algoritmo de firma ECDSA

```
Input:  mensaje M,  clave privada d (entero de 256 bits)
Output: firma (r, s) — dos enteros de 256 bits

1. hash = SHA-256(M)

2. Elegir k (nonce de firma):
   - RFC6979: k se genera determinísticamente desde (hash, d)
   - Esto evita el bug catastrófico de reutilizar k con mensajes distintos

3. R = k × G  (multiplicación escalar del punto generador de la curva)
   r = R.x mod n  (solo la coordenada x)

4. s = k⁻¹ × (hash + r × d) mod n

5. Salida: (r, s)
```

**Formato JWT para la firma:** `r || s` concatenados (32 bytes cada uno = 64 bytes total), codificados en base64url. No es DER — JWT usa el formato compacto R‖S.

### En Android — `JWTUtil.java`

```java
// 1. SHA-256 del signing input (header.payload)
MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
byte[] hash = sha256.digest(message);

// 2. Parámetros de curva secp256k1
ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
ECDomainParameters domain = new ECDomainParameters(
    spec.getCurve(), spec.getG(), spec.getN(), spec.getH());

// 3. Firmar con k determinístico RFC6979
BigInteger d = new BigInteger(1, privKeyBytes);
ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(d, domain);
ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
signer.init(true, privParams);
BigInteger[] rs = signer.generateSignature(hash);  // → [r, s]

// 4. Formato compacto R‖S (64 bytes)
byte[] rb = to32Bytes(rs[0]);
byte[] sb = to32Bytes(rs[1]);
byte[] sig = new byte[64];
System.arraycopy(rb, 0, sig, 0, 32);
System.arraycopy(sb, 0, sig, 32, 32);
```

### En Python — `gen-proof.sh`

```python
from cryptography.hazmat.primitives.asymmetric.ec import ECDSA
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

# signing_input = base64url(header) + "." + base64url(payload)
der_sig = priv_key.sign(signing_input.encode(), ECDSA(hashes.SHA256()))

# La librería devuelve DER. Convertir a formato compacto R‖S (64 bytes)
r, s = decode_dss_signature(der_sig)
sig_bytes = r.to_bytes(32, "big") + s.to_bytes(32, "big")
```

> `cryptography` en Python usa DER internamente y luego se convierte a R‖S. BouncyCastle en Android trabaja directamente con (r, s). El resultado es idéntico.

### Construcción del JWT

```
JWT = base64url(header_json) + "." + base64url(payload_json) + "." + base64url(firma)
```

Ambas implementaciones construyen el JWT de la misma forma:

```java
// Android — JWTUtil.java
String signingInput = Base64Url.encode(headerJson) + "." + Base64Url.encode(payloadJson);
byte[] sig = signES256K(signingInput.getBytes(), privKeyBytes);
return signingInput + "." + Base64Url.encode(sig);
```

```python
# Python — gen-proof.sh
signing_input = b64url(header_json) + "." + b64url(payload_json)
proof_jwt = signing_input + "." + b64url(sig_bytes)
```

---

## 6. Protección de la clave privada en Android

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

## 7. Equivalencia Android ↔ Python

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
