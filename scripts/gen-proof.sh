#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# gen-proof.sh — Genera el PROOF_JWT usando las claves del holder ya generadas
#
# Requisito previo: ejecutar gen-holder.sh para generar las claves y el DID.
#
# Uso:
#   ./scripts/gen-proof.sh <nonce> [base_url] [keys_file]
#
# Salida (para copiar en Postman):
#   HOLDER_DID=did:key:z...
#   PROOF_JWT=eyJ...
#
# Requisitos: python3 + pip install cryptography
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

NONCE="${1:-}"
BASE_URL="${2:-https://did-issuer-app.whitehill-96d25d6d.eastus2.azurecontainerapps.io}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
KEYS_FILE="${3:-$PROJECT_ROOT/.holder-keys}"

[ -z "$NONCE" ] && { echo "Uso: $0 <nonce> [base_url] [keys_file]" >&2; exit 1; }

if [ ! -f "$KEYS_FILE" ]; then
    echo "ERROR: No se encontró el archivo de claves: $KEYS_FILE" >&2
    echo "Ejecutá primero: ./scripts/gen-holder.sh" >&2
    exit 1
fi

# Leer claves del archivo
HOLDER_DID=$(grep '^HOLDER_DID=' "$KEYS_FILE" | cut -d= -f2-)
PRIV_HEX=$(grep '^HOLDER_PRIVATE_KEY_HEX=' "$KEYS_FILE" | cut -d= -f2-)

python3 - "$NONCE" "$BASE_URL" "$HOLDER_DID" "$PRIV_HEX" <<'PYEOF'
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

nonce      = sys.argv[1]
base_url   = sys.argv[2]
holder_did = sys.argv[3]
priv_hex   = sys.argv[4]

# Reconstruir clave privada desde hex
priv_int  = int(priv_hex, 16)
priv_key  = derive_private_key(priv_int, SECP256K1(), default_backend())

# Derivar kid desde el DID (did:key:z<encoded> → kid = did#<encoded>)
encoded = holder_did.split("did:key:")[1]
kid     = holder_did + "#" + encoded

def b64url(data):
    if isinstance(data, str):
        data = data.encode()
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

now = int(time.time())

header_json = json.dumps({
    "alg": "ES256K",
    "typ": "openid4vci-proof+jwt",
    "kid": kid
}, separators=(",", ":"))

payload_json = json.dumps({
    "iss":             holder_did,
    "aud":             base_url,
    "iat":             now,
    "exp":             now + 300,
    "nonce":           nonce,
    "credential_type": "UniversityDegreeCredential",
    "subject_claims": {
        "givenName":  "Juan",
        "familyName": "Pérez",
        "email":      "juan@example.com"
    }
}, separators=(",", ":"))

signing_input = b64url(header_json) + "." + b64url(payload_json)
der_sig       = priv_key.sign(signing_input.encode(), ECDSA(hashes.SHA256()))
r, s          = decode_dss_signature(der_sig)
sig_bytes     = r.to_bytes(32, "big") + s.to_bytes(32, "big")
proof_jwt     = signing_input + "." + b64url(sig_bytes)

print(f"HOLDER_DID={holder_did}")
print(f"PROOF_JWT={proof_jwt}")
print()
print("# ─── Variables para pegar en Postman ────────────────────────────────")
print(f"#   HOLDER_DID  = {holder_did}")
print(f"#   PROOF_JWT   = {proof_jwt[:60]}...")
PYEOF
