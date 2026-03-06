#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# gen-vp.sh — Genera una VP JWT (Verifiable Presentation)
#
# Requisito previo: ejecutar gen-holder.sh y haber recibido una VC del issuer.
#
# Uso:
#   ./scripts/gen-vp.sh <vc_jwt> [base_url] [keys_file]
#
#   <vc_jwt>     JWT de la VC a incluir en la presentación (obligatorio)
#   [base_url]   URL del backend, se usa como campo `aud` del VP JWT
#                (default: http://localhost:8080)
#   [keys_file]  Archivo de claves del holder (default: .holder-keys)
#
# Salida:
#   VP_JWT=eyJ...   ← copiar en Postman (variable VP_JWT) o usar en curl:
#
#   curl -s -X POST <base_url>/credentials/verify \
#     -H "Content-Type: application/json" \
#     -d "{\"vp_jwt\":\"$VP_JWT\"}" | jq .
#
# Requisitos: python3 + pip install cryptography
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

VC_JWT="${1:-}"
BASE_URL="${2:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
KEYS_FILE="${3:-$PROJECT_ROOT/.holder-keys}"

[ -z "$VC_JWT" ] && { echo "Uso: $0 <vc_jwt> [base_url] [keys_file]" >&2; exit 1; }

if [ ! -f "$KEYS_FILE" ]; then
    echo "ERROR: No se encontró el archivo de claves: $KEYS_FILE" >&2
    echo "Ejecutá primero: ./scripts/gen-holder.sh" >&2
    exit 1
fi

# Leer claves del archivo
HOLDER_DID=$(grep '^HOLDER_DID=' "$KEYS_FILE" | cut -d= -f2-)
PRIV_HEX=$(grep '^HOLDER_PRIVATE_KEY_HEX=' "$KEYS_FILE" | cut -d= -f2-)

# ─── Generar la VP JWT ────────────────────────────────────────────────────────
VP_JWT=$(python3 - "$VC_JWT" "$BASE_URL" "$HOLDER_DID" "$PRIV_HEX" <<'PYEOF'
import sys, json, base64, time

try:
    from cryptography.hazmat.primitives.asymmetric.ec import (
        derive_private_key, SECP256K1, ECDSA)
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.asymmetric.utils import (
        decode_dss_signature)
except ImportError:
    print("ERROR: pip install cryptography", file=sys.stderr)
    sys.exit(1)

vc_jwt     = sys.argv[1]
base_url   = sys.argv[2]
holder_did = sys.argv[3]
priv_hex   = sys.argv[4]

# Reconstruir clave privada desde hex
priv_int = int(priv_hex, 16)
priv_key = derive_private_key(priv_int, SECP256K1(), default_backend())

# kid del holder
encoded = holder_did.split("did:key:")[1]
kid     = holder_did + "#" + encoded

def b64url(data):
    if isinstance(data, str):
        data = data.encode()
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

now = int(time.time())

header_json = json.dumps({
    "alg": "ES256K",
    "typ": "JWT",
    "kid": kid
}, separators=(",", ":"))

payload_json = json.dumps({
    "iss": holder_did,
    "aud": base_url,
    "iat": now,
    "exp": now + 300,
    "vp": {
        "type": ["VerifiablePresentation"],
        "verifiableCredential": [vc_jwt]
    }
}, separators=(",", ":"))

signing_input = b64url(header_json) + "." + b64url(payload_json)
der_sig       = priv_key.sign(signing_input.encode(), ECDSA(hashes.SHA256()))
r, s          = decode_dss_signature(der_sig)
sig_bytes     = r.to_bytes(32, "big") + s.to_bytes(32, "big")
print(signing_input + "." + b64url(sig_bytes))
PYEOF
)

echo ""
echo "VP_JWT=${VP_JWT}"
echo ""
echo "# ─── Para verificar la VP contra el backend ─────────────────────────────"
echo "# curl -s -X POST ${BASE_URL}/credentials/verify \\"
echo "#   -H \"Content-Type: application/json\" \\"
echo "#   -d \"{\\\"vp_jwt\\\":\\\"${VP_JWT}\\\"}\" | jq ."
echo ""
