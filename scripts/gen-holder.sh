#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# gen-holder.sh — Genera un par de claves secp256k1 y deriva el DID del holder
#
# Uso:
#   ./scripts/gen-holder.sh [output_file]
#
# Salida (imprime en pantalla y guarda en .holder-keys por defecto):
#   HOLDER_DID=did:key:z...
#   HOLDER_PRIVATE_KEY_HEX=<64 hex chars>
#   HOLDER_PUBLIC_KEY_HEX=<66 hex chars>
#
# El archivo .holder-keys se usa luego por gen-proof.sh para firmar el JWT.
#
# Requisitos: python3 + pip install cryptography
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

OUTPUT_FILE="${1:-.holder-keys}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_PATH="$PROJECT_ROOT/$OUTPUT_FILE"

python3 - "$OUTPUT_PATH" <<'PYEOF'
import sys, json, base64

try:
    from cryptography.hazmat.primitives.asymmetric.ec import (
        generate_private_key, SECP256K1)
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PublicFormat, PrivateFormat, NoEncryption)
except ImportError:
    print("ERROR: pip install cryptography", file=sys.stderr)
    sys.exit(1)

output_path = sys.argv[1]

# Base58btc
ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58encode(data: bytes) -> str:
    n = int.from_bytes(data, "big")
    result = []
    while n > 0:
        n, r = divmod(n, 58)
        result.append(ALPHABET[r:r+1])
    leading = len(data) - len(data.lstrip(b"\x00"))
    return (ALPHABET[0:1] * leading + b"".join(reversed(result))).decode()

# Generar clave secp256k1
priv_key  = generate_private_key(SECP256K1(), default_backend())
pub_key   = priv_key.public_key()
pub_bytes = pub_key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint)

# Clave privada en formato raw (32 bytes)
priv_bytes = priv_key.private_bytes(Encoding.PEM, PrivateFormat.TraditionalOpenSSL, NoEncryption())

# Derivar DID (did:key:z + multibase base58btc)
multicodec = bytes([0xe7, 0x01]) + pub_bytes
encoded    = "z" + b58encode(multicodec)
holder_did = "did:key:" + encoded

# Exportar clave privada como hex (32 bytes = entero privado)
priv_int   = priv_key.private_numbers().private_value
priv_hex   = priv_int.to_bytes(32, "big").hex()
pub_hex    = pub_bytes.hex()

# Guardar en archivo
with open(output_path, "w") as f:
    f.write(f"HOLDER_DID={holder_did}\n")
    f.write(f"HOLDER_PRIVATE_KEY_HEX={priv_hex}\n")
    f.write(f"HOLDER_PUBLIC_KEY_HEX={pub_hex}\n")

print(f"HOLDER_DID={holder_did}")
print(f"HOLDER_PUBLIC_KEY_HEX={pub_hex}")
print()
print(f"✓ Claves guardadas en: {output_path}")
print(f"  (la clave privada está en el archivo — no compartir)")
print()
print("# ─── Próximo paso ───────────────────────────────────────────────────")
print(f"# 1. Obtener nonce:")
print(f"#      GET /credentials/nonce?holder_did={holder_did}")
print(f"# 2. Generar proof JWT con el nonce:")
print(f"#      ./scripts/gen-proof.sh <nonce>")
PYEOF
