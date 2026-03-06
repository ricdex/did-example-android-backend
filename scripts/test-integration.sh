#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test-integration.sh — Pruebas de integración end-to-end via curl
#
# Flujos cubiertos:
#   0. Registro y verificación de DID del holder
#   1. Identidad del Issuer
#   2. Obtención de Verifiable Credential
#   2b. Seguridad: anti-replay
#   3. Metadatos del Holder
#   4. Revocación de VC
#   5. Invalidación de DID
#   6. Verificación de VP (válida, con VC revocada, sin vp_jwt)
#
# Uso:
#   ./scripts/test-integration.sh               # apunta a localhost:8080
#   ./scripts/test-integration.sh http://1.2.3.4:8080
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
PASS=0
FAIL=0

# ─── Reporte ──────────────────────────────────────────────────────────────────
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
REPORT_DIR="$(cd "$(dirname "$0")/.." && pwd)/reports"
mkdir -p "$REPORT_DIR"
REPORT_FILE="$REPORT_DIR/test-report-curl-${TIMESTAMP}.md"

LAST_STATUS=""
LAST_BODY=""

# ─── Colores ──────────────────────────────────────────────────────────────────
green()  { echo -e "\033[32m✓ $*\033[0m"; }
red()    { echo -e "\033[31m✗ $*\033[0m"; }
blue()   { echo -e "\033[34m▶ $*\033[0m"; }
yellow() { echo -e "\033[33m  $*\033[0m"; }
divider(){ echo -e "\033[90m────────────────────────────────────────────\033[0m"; }

# ─── Assert helpers ───────────────────────────────────────────────────────────
assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    green "$label"
    echo "- ✓ $label" >> "$REPORT_FILE"
    PASS=$((PASS + 1))
  else
    red "$label (esperado='$expected' obtenido='$actual')"
    echo "- ✗ $label — esperado \`$expected\`, obtenido \`$actual\`" >> "$REPORT_FILE"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local label="$1" needle="$2" haystack="$3"
  if echo "$haystack" | grep -q "$needle"; then
    green "$label"
    echo "- ✓ $label" >> "$REPORT_FILE"
    PASS=$((PASS + 1))
  else
    red "$label (no contiene '$needle')"
    echo "- ✗ $label — no contiene \`$needle\`" >> "$REPORT_FILE"
    yellow "Respuesta: $haystack"
    FAIL=$((FAIL + 1))
  fi
}

assert_not_contains() {
  local label="$1" needle="$2" haystack="$3"
  if ! echo "$haystack" | grep -q "$needle"; then
    green "$label"
    echo "- ✓ $label" >> "$REPORT_FILE"
    PASS=$((PASS + 1))
  else
    red "$label (no debería contener '$needle')"
    echo "- ✗ $label — contiene \`$needle\` cuando no debería" >> "$REPORT_FILE"
    FAIL=$((FAIL + 1))
  fi
}

# ─── HTTP helper ──────────────────────────────────────────────────────────────
do_request() {
  local method="$1" url="$2" content_type="${3:-}" body="${4:-}"
  local curl_args=(-s -X "$method")
  [ -n "$content_type" ] && curl_args+=(-H "Content-Type: $content_type")
  [ -n "$body" ]         && curl_args+=(-d "$body")

  local tmp
  tmp=$(mktemp)
  LAST_STATUS=$(curl "${curl_args[@]}" --max-time 30 -o "$tmp" -w "%{http_code}" "$url" 2>/dev/null || echo "000")
  LAST_BODY=$(cat "$tmp")
  rm -f "$tmp"
}

# ─── Formateo JSON ────────────────────────────────────────────────────────────
pretty_json() {
  echo "$1" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin), indent=2, ensure_ascii=False))" 2>/dev/null \
    || echo "$1"
}

decode_jwt_display() {
  local jwt="$1"
  python3 - "$jwt" <<'PYEOF'
import sys, json, base64

jwt = sys.argv[1]
parts = jwt.split(".")
if len(parts) != 3:
    print(json.dumps({"jwt": jwt[:80] + "..."}, indent=2))
    sys.exit(0)

def b64d(s):
    s += "=" * ((4 - len(s) % 4) % 4)
    return base64.urlsafe_b64decode(s)

try:
    h = json.loads(b64d(parts[0]))
    p = json.loads(b64d(parts[1]))
    print(json.dumps({"header": h, "payload": p, "signature": "[omitida]"}, indent=2, ensure_ascii=False))
except Exception:
    print(json.dumps({"jwt": jwt[:80] + "..."}, indent=2))
PYEOF
}

# ─── Reporte helpers ──────────────────────────────────────────────────────────
report_section() {
  printf '\n## %s\n\n' "$1" >> "$REPORT_FILE"
}

log_request() {
  local title="$1" method="$2" url="$3" req_display="${4:-}"
  local pretty_resp
  pretty_resp=$(pretty_json "$LAST_BODY")
  {
    printf '### %s\n\n' "$title"
    printf '**Request**\n\n```\n%s %s\n' "$method" "$url"
    if [ -n "$req_display" ]; then
      printf 'Content-Type: application/json\n\n%s\n' "$req_display"
    fi
    printf '```\n\n'
    printf '**Response** — HTTP %s\n\n```json\n%s\n```\n\n' "$LAST_STATUS" "$pretty_resp"
    printf '**Aserciones:**\n\n'
  } >> "$REPORT_FILE"
}

log_section_end() {
  printf '\n---\n' >> "$REPORT_FILE"
}

# ─── Dependencias ─────────────────────────────────────────────────────────────
check_deps() {
  for cmd in curl jq python3; do
    command -v "$cmd" >/dev/null || { red "Falta: $cmd"; exit 1; }
  done
  python3 -c "import cryptography" 2>/dev/null || {
    yellow "Instalando 'cryptography' para Python..."
    pip3 install cryptography --quiet
  }
}

# ─── Generador de claves del holder ──────────────────────────────────────────
# Genera un par de claves secp256k1 y devuelve did, public_key_hex, private_key_hex
generate_holder_keys() {
  python3 <<'PYEOF'
import json, base64

try:
    from cryptography.hazmat.primitives.asymmetric.ec import (
        generate_private_key, SECP256K1)
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PublicFormat)
except ImportError:
    print(json.dumps({"error": "pip install cryptography"}))
    import sys; sys.exit(1)

ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58encode(data):
    n = int.from_bytes(data, "big")
    result = []
    while n > 0:
        n, r = divmod(n, 58)
        result.append(ALPHABET[r:r+1])
    leading = len(data) - len(data.lstrip(b"\x00"))
    return (ALPHABET[0:1] * leading + b"".join(reversed(result))).decode()

priv_key  = generate_private_key(SECP256K1(), default_backend())
pub_key   = priv_key.public_key()
pub_bytes = pub_key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint)

multicodec = bytes([0xe7, 0x01]) + pub_bytes
encoded    = "z" + b58encode(multicodec)
did        = "did:key:" + encoded

priv_int = priv_key.private_numbers().private_value
priv_hex = priv_int.to_bytes(32, "big").hex()

print(json.dumps({
    "did":             did,
    "public_key_hex":  pub_bytes.hex(),
    "private_key_hex": priv_hex
}))
PYEOF
}

# ─── Generador de Proof JWT con clave existente ───────────────────────────────
# sign_proof PRIVATE_KEY_HEX NONCE ISSUER_URL [CREDENTIAL_TYPE]
sign_proof() {
  local priv_hex="$1" nonce="$2" issuer_url="$3" credential_type="${4:-VerifiableCredential}"

  python3 - "$priv_hex" "$nonce" "$issuer_url" "$credential_type" <<'PYEOF'
import sys, json, base64, time

try:
    from cryptography.hazmat.primitives.asymmetric.ec import (
        derive_private_key, SECP256K1, ECDSA)
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PublicFormat)
    from cryptography.hazmat.primitives.asymmetric.utils import (
        decode_dss_signature)
except ImportError:
    print(json.dumps({"error": "pip install cryptography"}))
    sys.exit(1)

priv_hex        = sys.argv[1]
nonce           = sys.argv[2]
issuer_url      = sys.argv[3]
credential_type = sys.argv[4]

ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58encode(data):
    n = int.from_bytes(data, "big")
    result = []
    while n > 0:
        n, r = divmod(n, 58)
        result.append(ALPHABET[r:r+1])
    leading = len(data) - len(data.lstrip(b"\x00"))
    return (ALPHABET[0:1] * leading + b"".join(reversed(result))).decode()

def b64url(data):
    if isinstance(data, str):
        data = data.encode()
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

priv_int = int.from_bytes(bytes.fromhex(priv_hex), "big")
priv_key = derive_private_key(priv_int, SECP256K1(), default_backend())
pub_key  = priv_key.public_key()
pub_bytes = pub_key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint)

multicodec = bytes([0xe7, 0x01]) + pub_bytes
encoded    = "z" + b58encode(multicodec)
did        = "did:key:" + encoded
kid        = did + "#" + encoded

now = int(time.time())

header_json = json.dumps({
    "alg": "ES256K",
    "typ": "openid4vci-proof+jwt",
    "kid": kid
}, separators=(",", ":"))

payload_json = json.dumps({
    "iss": did,
    "aud": issuer_url,
    "iat": now,
    "exp": now + 300,
    "nonce": nonce,
    "credential_type": credential_type,
    "subject_claims": {
        "givenName":  "Juan",
        "familyName": "Pérez",
        "email":      "juan@example.com"
    }
}, separators=(",", ":"))

signing_input = b64url(header_json) + "." + b64url(payload_json)
der_sig  = priv_key.sign(signing_input.encode(), ECDSA(hashes.SHA256()))
r, s     = decode_dss_signature(der_sig)
sig_bytes = r.to_bytes(32, "big") + s.to_bytes(32, "big")
jwt       = signing_input + "." + b64url(sig_bytes)

print(jwt)
PYEOF
}

# ─── Esperar backend ──────────────────────────────────────────────────────────
wait_for_backend() {
  blue "Esperando al backend en $BASE_URL (cold start Java puede tardar ~60s)..."
  for i in $(seq 1 40); do
    if curl -sf --max-time 10 "$BASE_URL/issuer/did" >/dev/null 2>&1; then
      green "Backend disponible"
      return 0
    fi
    sleep 5
    echo -n " $i"
  done
  red "Backend no responde en $BASE_URL tras ~200s"
  exit 1
}

# ═════════════════════════════════════════════════════════════════════════════
# INICIO
# ═════════════════════════════════════════════════════════════════════════════

check_deps

cat > "$REPORT_FILE" <<HEADER
# Reporte de pruebas de integración (curl)

**Fecha:** $(date "+%Y-%m-%d %H:%M:%S")
**Backend:** $BASE_URL

---
HEADER

echo ""
echo "══════════════════════════════════════════════"
echo " DID Issuer — Pruebas de integración (curl)"
echo " Backend: $BASE_URL"
echo "══════════════════════════════════════════════"
echo ""

wait_for_backend
echo ""

# ─── Generar claves del holder (una sola vez para todos los flujos) ────────────
yellow "Generando par de claves secp256k1 para el holder de prueba..."
HOLDER_KEYS=$(generate_holder_keys)
HOLDER_DID=$(echo "$HOLDER_KEYS"  | jq -r '.did')
HOLDER_PRIV=$(echo "$HOLDER_KEYS" | jq -r '.private_key_hex')
yellow "Holder DID: $HOLDER_DID"
echo ""

# Generar un segundo holder para el flujo de invalidación
HOLDER2_KEYS=$(generate_holder_keys)
HOLDER2_DID=$(echo "$HOLDER2_KEYS"  | jq -r '.did')
HOLDER2_PRIV=$(echo "$HOLDER2_KEYS" | jq -r '.private_key_hex')
yellow "Holder2 DID (para prueba de invalidación): $HOLDER2_DID"
echo ""

# ─── Flujo 0: Registro de DID del Holder ─────────────────────────────────────
blue "FLUJO 0 — Registro de DID del Holder"
divider
report_section "Flujo 0 — Registro de DID del Holder"

CLIENT_ID="test-client@example.com"

# 0a. POST /dids/register — registrar holder principal
REGISTER_BODY="{\"client_id\":\"$CLIENT_ID\",\"did\":\"$HOLDER_DID\"}"
do_request "POST" "$BASE_URL/dids/register" "application/json" "$REGISTER_BODY"
log_request "POST /dids/register" "POST" "$BASE_URL/dids/register" \
  "$(pretty_json "$REGISTER_BODY")"

assert_eq       "POST /dids/register → HTTP 200"            "200"           "$LAST_STATUS"
assert_contains "Respuesta contiene 'did'"                  '"did"'         "$LAST_BODY"
assert_contains "Respuesta contiene 'client_id'"            '"client_id"'   "$LAST_BODY"
assert_contains "Respuesta contiene 'active'"               '"active"'      "$LAST_BODY"
assert_contains "DID registrado coincide"                   "$HOLDER_DID"   "$LAST_BODY"
assert_contains "client_id coincide"                        "$CLIENT_ID"    "$LAST_BODY"

ACTIVE_VAL=$(echo "$LAST_BODY" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('active',''))" 2>/dev/null || echo "")
assert_eq "DID registrado como activo" "True" "$ACTIVE_VAL"
log_section_end

# 0b. POST /dids/register — idempotente (mismo DID dos veces → 200)
do_request "POST" "$BASE_URL/dids/register" "application/json" "$REGISTER_BODY"
log_request "POST /dids/register — idempotente" "POST" "$BASE_URL/dids/register" \
  "$(pretty_json "$REGISTER_BODY")"
assert_eq "Registro idempotente → HTTP 200" "200" "$LAST_STATUS"
log_section_end

# 0c. GET /dids/{did} — verificar estado activo
do_request "GET" "$BASE_URL/dids/$HOLDER_DID"
log_request "GET /dids/{did}" "GET" "$BASE_URL/dids/$HOLDER_DID"

assert_eq       "GET /dids/{did} → HTTP 200"                "200"    "$LAST_STATUS"
assert_contains "Estado contiene 'active'"                  '"active"' "$LAST_BODY"
ACTIVE_DID_VAL=$(echo "$LAST_BODY" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('active',''))" 2>/dev/null || echo "")
assert_eq "DID está activo" "True" "$ACTIVE_DID_VAL"
log_section_end

# 0d. GET /clients/{clientId}/dids — listar DIDs del cliente
do_request "GET" "$BASE_URL/clients/$CLIENT_ID/dids"
log_request "GET /clients/{clientId}/dids" "GET" "$BASE_URL/clients/$CLIENT_ID/dids"

assert_eq       "GET /clients/{clientId}/dids → HTTP 200"   "200"           "$LAST_STATUS"
assert_contains "Lista contiene nuestro DID"                "$HOLDER_DID"   "$LAST_BODY"
log_section_end

# 0e. POST /dids/register — DID no registrado sin method did:key → 400
do_request "POST" "$BASE_URL/dids/register" "application/json" \
  "{\"client_id\":\"$CLIENT_ID\",\"did\":\"did:web:example.com\"}"
assert_eq "DID con método no soportado → HTTP 400" "400" "$LAST_STATUS"
log_section_end

# 0f. Registrar holder2 (para flujo 5)
REGISTER2_BODY="{\"client_id\":\"$CLIENT_ID\",\"did\":\"$HOLDER2_DID\"}"
do_request "POST" "$BASE_URL/dids/register" "application/json" "$REGISTER2_BODY"
assert_eq "Registrar holder2 → HTTP 200" "200" "$LAST_STATUS"
log_section_end
echo ""

# ─── Flujo 1: Identidad del Issuer ───────────────────────────────────────────
blue "FLUJO 1 — Identidad del Issuer"
divider
report_section "Flujo 1 — Identidad del Issuer"

do_request "GET" "$BASE_URL/issuer/did"
log_request "GET /issuer/did" "GET" "$BASE_URL/issuer/did"

assert_eq       "GET /issuer/did → HTTP 200"                       "200"              "$LAST_STATUS"
assert_contains "Respuesta contiene 'did'"                         '"did"'            "$LAST_BODY"
assert_contains "Respuesta contiene 'public_key_hex'"              '"public_key_hex"' "$LAST_BODY"

ISSUER_DID=$(echo "$LAST_BODY" | jq -r '.did')
assert_contains "DID comienza con did:key:z"                       "did:key:z"        "$ISSUER_DID"

PUB_HEX=$(echo "$LAST_BODY" | jq -r '.public_key_hex')
assert_eq       "Clave pública = 66 hex chars (33 bytes comprimidos)" "66"            "${#PUB_HEX}"

yellow "Issuer DID: $ISSUER_DID"
log_section_end

do_request "GET" "$BASE_URL/issuer/did-document"
log_request "GET /issuer/did-document" "GET" "$BASE_URL/issuer/did-document"

assert_eq       "GET /issuer/did-document → HTTP 200"              "200"                 "$LAST_STATUS"
assert_contains "DID Document contiene @context"                   '@context'            "$LAST_BODY"
assert_contains "DID Document contiene verificationMethod"         'verificationMethod'  "$LAST_BODY"
assert_contains "DID Document contiene EcdsaSecp256k1"             'EcdsaSecp256k1'      "$LAST_BODY"

log_section_end
echo ""

# ─── Flujo 2: Obtención de Verifiable Credential ─────────────────────────────
blue "FLUJO 2 — Obtención de Verifiable Credential"
divider
report_section "Flujo 2 — Obtención de Verifiable Credential"

# 2a. GET /credentials/nonce?holder_did=... (DID debe estar registrado y activo)
do_request "GET" "$BASE_URL/credentials/nonce?holder_did=$HOLDER_DID"
log_request "GET /credentials/nonce?holder_did" "GET" \
  "$BASE_URL/credentials/nonce?holder_did=$HOLDER_DID"

assert_eq      "GET /credentials/nonce → HTTP 200"   "200"     "$LAST_STATUS"
assert_contains "Respuesta contiene 'nonce'"         '"nonce"' "$LAST_BODY"

NONCE=$(echo "$LAST_BODY" | jq -r '.nonce')
assert_contains "Nonce no está vacío"                '.'       "$NONCE"
yellow "Nonce obtenido: $NONCE"
log_section_end

# 2b. Nonce sin holder_did → 400
do_request "GET" "$BASE_URL/credentials/nonce"
assert_eq "GET /credentials/nonce sin holder_did → HTTP 400" "400" "$LAST_STATUS"
log_section_end

# 2c. Firmar Proof JWT con la clave del holder registrado
yellow "Firmando Proof JWT con clave del holder registrado..."
PROOF_JWT=$(sign_proof "$HOLDER_PRIV" "$NONCE" "$BASE_URL" "UniversityDegreeCredential")

# 2d. POST /credentials/issue
ISSUE_BODY="{\"holder_did\":\"$HOLDER_DID\",\"proof\":\"$PROOF_JWT\"}"
do_request "POST" "$BASE_URL/credentials/issue" "application/json" "$ISSUE_BODY"

DECODED_PROOF=$(decode_jwt_display "$PROOF_JWT")
ISSUE_DISPLAY=$(python3 - "$HOLDER_DID" "$DECODED_PROOF" <<'PYEOF'
import sys, json
holder_did    = sys.argv[1]
decoded_proof = json.loads(sys.argv[2])
print(json.dumps({"holder_did": holder_did, "proof": decoded_proof}, indent=2, ensure_ascii=False))
PYEOF
)

VC_RESP_DISPLAY="$LAST_BODY"
if echo "$LAST_BODY" | jq -e '.credential' >/dev/null 2>&1; then
  VC_RAW=$(echo "$LAST_BODY" | jq -r '.credential')
  DECODED_VC=$(decode_jwt_display "$VC_RAW")
  VC_RESP_DISPLAY=$(python3 - "$DECODED_VC" <<'PYEOF'
import sys, json
vc = json.loads(sys.argv[1])
print(json.dumps({"credential": vc}, indent=2, ensure_ascii=False))
PYEOF
)
fi

{
  printf '### POST /credentials/issue\n\n'
  printf '**Request**\n\n```\nPOST %s/credentials/issue\nContent-Type: application/json\n\n%s\n```\n\n' \
    "$BASE_URL" "$ISSUE_DISPLAY"
  printf '**Response** — HTTP %s\n\n```json\n%s\n```\n\n' "$LAST_STATUS" "$VC_RESP_DISPLAY"
  printf '**Aserciones:**\n\n'
} >> "$REPORT_FILE"

ISSUE_STATUS="$LAST_STATUS"
ISSUE_BODY_RESP="$LAST_BODY"

assert_eq      "POST /credentials/issue → HTTP 200"                      "200"                        "$ISSUE_STATUS"
assert_contains "Respuesta contiene 'credential'"                        '"credential"'               "$ISSUE_BODY_RESP"

VC_JWT=$(echo "$ISSUE_BODY_RESP" | jq -r '.credential // empty')
VC_PARTS=$(echo "$VC_JWT" | tr '.' '\n' | wc -l | tr -d ' ')
assert_eq      "VC JWT tiene 3 partes (header.payload.signature)"        "3"                          "$VC_PARTS"

VC_PAYLOAD=$(python3 - "$VC_JWT" <<'PYEOF'
import sys, json, base64
jwt = sys.argv[1]
part = jwt.split(".")[1]
part += "=" * ((4 - len(part) % 4) % 4)
print(base64.urlsafe_b64decode(part).decode("utf-8"))
PYEOF
)
assert_contains "VC contiene el DID del holder como 'sub'"               "$HOLDER_DID"                "$VC_PAYLOAD"
assert_contains "VC contiene 'UniversityDegreeCredential'"               "UniversityDegreeCredential" "$VC_PAYLOAD"
assert_contains "VC contiene 'iss' (DID del issuer)"                     '"iss"'                      "$VC_PAYLOAD"
assert_contains "VC contiene 'credentialSubject'"                        "credentialSubject"          "$VC_PAYLOAD"
assert_contains "VC contiene claims del sujeto"                          "Juan"                       "$VC_PAYLOAD"

yellow "VC emitida correctamente"
log_section_end
echo ""

# ─── Flujo 2b: Seguridad — Anti-replay ───────────────────────────────────────
blue "FLUJO 2b — Anti-replay: nonce reutilizado debe ser rechazado"
divider
report_section "Flujo 2b — Anti-replay"

# Reusar el mismo proof JWT (nonce ya consumido)
do_request "POST" "$BASE_URL/credentials/issue" "application/json" \
  "{\"holder_did\":\"$HOLDER_DID\",\"proof\":\"$PROOF_JWT\"}"

{
  printf '### POST /credentials/issue — nonce reutilizado\n\n'
  printf '**Request**\n\n```\nPOST %s/credentials/issue\nContent-Type: application/json\n\n%s\n```\n\n' \
    "$BASE_URL" "{ mismo proof JWT del flujo anterior — nonce ya consumido }"
  printf '**Response** — HTTP %s\n\n```json\n%s\n```\n\n' "$LAST_STATUS" "$(pretty_json "$LAST_BODY")"
  printf '**Aserciones:**\n\n'
} >> "$REPORT_FILE"

assert_eq "POST /credentials/issue con nonce consumido → HTTP 400" "400" "$LAST_STATUS"
log_section_end

# DID no registrado → rechazado al intentar emitir (antes de verificar nonce)
UNREG_KEYS=$(generate_holder_keys)
UNREG_DID=$(echo "$UNREG_KEYS"  | jq -r '.did')
UNREG_PRIV=$(echo "$UNREG_KEYS" | jq -r '.private_key_hex')
FAKE_PROOF=$(sign_proof "$UNREG_PRIV" "nonce-inventado-$(date +%s)" "$BASE_URL")

do_request "POST" "$BASE_URL/credentials/issue" "application/json" \
  "{\"holder_did\":\"$UNREG_DID\",\"proof\":\"$FAKE_PROOF\"}"

{
  printf '### POST /credentials/issue — DID no registrado\n\n'
  printf '**Response** — HTTP %s\n\n```json\n%s\n```\n\n' "$LAST_STATUS" "$(pretty_json "$LAST_BODY")"
  printf '**Aserciones:**\n\n'
} >> "$REPORT_FILE"

assert_eq "POST /credentials/issue con DID no registrado → HTTP 400" "400" "$LAST_STATUS"
log_section_end
echo ""

# ─── Flujo 3: Metadatos del Holder ───────────────────────────────────────────
blue "FLUJO 3 — Metadatos de credenciales del Holder"
divider
report_section "Flujo 3 — Metadatos del Holder"

do_request "GET" "$BASE_URL/credentials?holder_did=$HOLDER_DID"
log_request "GET /credentials?holder_did" "GET" \
  "$BASE_URL/credentials?holder_did=$HOLDER_DID"

assert_eq       "GET /credentials?holder_did → HTTP 200"        "200"             "$LAST_STATUS"
assert_contains "Metadatos contienen 'credential_id'"           "credential_id"   "$LAST_BODY"
assert_contains "Metadatos contienen 'credential_type'"         "credential_type" "$LAST_BODY"
assert_contains "Metadatos contienen 'issued_at'"               "issued_at"       "$LAST_BODY"
assert_contains "Metadatos contienen 'expires_at'"              "expires_at"      "$LAST_BODY"
assert_contains "Metadatos contienen 'revoked'"                 "revoked"         "$LAST_BODY"
assert_not_contains "Metadatos NO exponen el JWT completo"      "$VC_JWT"         "$LAST_BODY"
assert_not_contains "Metadatos no tienen campo 'credential'"    '"credential":'   "$LAST_BODY"

yellow "Metadatos: $(echo "$LAST_BODY" | jq -c '.[0]')"
log_section_end
echo ""

# ─── Flujo 4: Revocación ─────────────────────────────────────────────────────
blue "FLUJO 4 — Revocación de Verifiable Credential"
divider
report_section "Flujo 4 — Revocación de Verifiable Credential"

CREDENTIAL_ID=$(python3 - "$VC_JWT" <<'PYEOF'
import sys, json, base64
jwt = sys.argv[1]
part = jwt.split(".")[1]
part += "=" * ((4 - len(part) % 4) % 4)
payload = json.loads(base64.urlsafe_b64decode(part).decode("utf-8"))
print(payload.get("jti", ""))
PYEOF
)
yellow "Credential ID: $CREDENTIAL_ID"

# 4a. POST /credentials/{credentialId}/revoke → 204
do_request "POST" "$BASE_URL/credentials/${CREDENTIAL_ID}/revoke"
log_request "POST /credentials/{credentialId}/revoke" \
  "POST" "$BASE_URL/credentials/${CREDENTIAL_ID}/revoke"
assert_eq "POST /credentials/{id}/revoke → HTTP 204" "204" "$LAST_STATUS"
log_section_end

# 4b. GET /credentials → verificar revoked=true
do_request "GET" "$BASE_URL/credentials?holder_did=$HOLDER_DID"
log_request "GET /credentials?holder_did (verificar revoked=true)" \
  "GET" "$BASE_URL/credentials?holder_did=$HOLDER_DID"
assert_eq "GET /credentials?holder_did → HTTP 200" "200" "$LAST_STATUS"

REVOKED_VAL=$(echo "$LAST_BODY" | python3 -c \
  "import sys,json; data=json.load(sys.stdin); print(data[0].get('revoked','')) if data else print('')" 2>/dev/null || echo "")
assert_eq "Metadatos muestran revoked=true" "True" "$REVOKED_VAL"
log_section_end

# 4c. Credencial inexistente → 404
FAKE_ID="urn:uuid:00000000-0000-0000-0000-000000000000"
do_request "POST" "$BASE_URL/credentials/${FAKE_ID}/revoke"
log_request "POST /credentials/{fake-id}/revoke — credencial inexistente" \
  "POST" "$BASE_URL/credentials/${FAKE_ID}/revoke"
assert_eq "POST /credentials/{fake-id}/revoke → HTTP 404" "404" "$LAST_STATUS"
log_section_end
echo ""

# ─── Flujo 5: Invalidación de DID ────────────────────────────────────────────
blue "FLUJO 5 — Invalidación de DID del Holder"
divider
report_section "Flujo 5 — Invalidación de DID del Holder"

# 5a. POST /dids/{did}/invalidate → 204
do_request "POST" "$BASE_URL/dids/$HOLDER2_DID/invalidate"
log_request "POST /dids/{did}/invalidate" "POST" "$BASE_URL/dids/$HOLDER2_DID/invalidate"
assert_eq "POST /dids/{did}/invalidate → HTTP 204" "204" "$LAST_STATUS"
log_section_end

# 5b. GET /dids/{did} → active=false
do_request "GET" "$BASE_URL/dids/$HOLDER2_DID"
log_request "GET /dids/{did} — DID invalidado" "GET" "$BASE_URL/dids/$HOLDER2_DID"
assert_eq "GET /dids/{did} invalidado → HTTP 200" "200" "$LAST_STATUS"
INACTIVE_VAL=$(echo "$LAST_BODY" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('active',''))" 2>/dev/null || echo "")
assert_eq "DID invalidado muestra active=false" "False" "$INACTIVE_VAL"
assert_contains "DID invalidado tiene 'invalidated_at'" "invalidated_at" "$LAST_BODY"
log_section_end

# 5c. GET /credentials/nonce con DID invalidado → 400
do_request "GET" "$BASE_URL/credentials/nonce?holder_did=$HOLDER2_DID"
log_request "GET /credentials/nonce — DID invalidado" "GET" \
  "$BASE_URL/credentials/nonce?holder_did=$HOLDER2_DID"
assert_eq "Nonce para DID invalidado → HTTP 400" "400" "$LAST_STATUS"
log_section_end

# 5d. POST /dids/{did}/invalidate para DID inexistente → 404
NONEXIST_DID="did:key:zQ3shFAKEDIDthatDoesNotExist123456789"
do_request "POST" "$BASE_URL/dids/$NONEXIST_DID/invalidate"
assert_eq "Invalidar DID inexistente → HTTP 404" "404" "$LAST_STATUS"
log_section_end
echo ""

# ─── Flujo 6: Verificación de VP ─────────────────────────────────────────────
blue "FLUJO 6 — Verificación de Verifiable Presentation"
divider
report_section "Flujo 6 — Verificación de Verifiable Presentation"

# Emitir una VC fresca para el holder (la del flujo 2 ya fue revocada)
yellow "Emitiendo VC fresca para construir la VP de verificación..."
do_request "GET" "$BASE_URL/credentials/nonce?holder_did=$HOLDER_DID"
NONCE2=$(echo "$LAST_BODY" | jq -r '.nonce')
PROOF2=$(sign_proof "$HOLDER_PRIV" "$NONCE2" "$BASE_URL" "UniversityDegreeCredential")
do_request "POST" "$BASE_URL/credentials/issue" "application/json" \
  "{\"holder_did\":\"$HOLDER_DID\",\"proof\":\"$PROOF2\"}"
VC_FRESH=$(echo "$LAST_BODY" | jq -r '.credential // empty')
yellow "VC fresca emitida"

# Construir VP JWT con la VC fresca
build_vp() {
  local priv_hex="$1" holder_did="$2" vc_jwt="$3" aud="$4"
  python3 - "$priv_hex" "$holder_did" "$vc_jwt" "$aud" <<'PYEOF'
import sys, json, base64, time
try:
    from cryptography.hazmat.primitives.asymmetric.ec import derive_private_key, SECP256K1, ECDSA
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
except ImportError:
    print(""); sys.exit(1)

ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58encode(data):
    n = int.from_bytes(data, "big"); result = []
    while n > 0:
        n, r = divmod(n, 58); result.append(ALPHABET[r:r+1])
    leading = len(data) - len(data.lstrip(b"\x00"))
    return (ALPHABET[0:1] * leading + b"".join(reversed(result))).decode()

def b64url(data):
    if isinstance(data, str): data = data.encode()
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

priv_hex, holder_did, vc_jwt, aud = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
priv_int = int.from_bytes(bytes.fromhex(priv_hex), "big")
priv_key = derive_private_key(priv_int, SECP256K1(), default_backend())
pub_key  = priv_key.public_key()
pub_bytes = pub_key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint)
encoded = "z" + b58encode(bytes([0xe7, 0x01]) + pub_bytes)
kid = "did:key:" + encoded + "#" + encoded
now = int(time.time())
header  = json.dumps({"alg":"ES256K","typ":"JWT","kid":kid}, separators=(",",":"))
payload = json.dumps({"iss":holder_did,"aud":aud,"iat":now,"exp":now+300,
    "vp":{"type":["VerifiablePresentation"],"verifiableCredential":[vc_jwt]}},
    separators=(",",":"))
signing_input = b64url(header) + "." + b64url(payload)
der_sig = priv_key.sign(signing_input.encode(), ECDSA(hashes.SHA256()))
r, s = decode_dss_signature(der_sig)
print(signing_input + "." + b64url(r.to_bytes(32,"big") + s.to_bytes(32,"big")))
PYEOF
}

VP_VALID=$(build_vp "$HOLDER_PRIV" "$HOLDER_DID" "$VC_FRESH" "$BASE_URL")
# VP con la VC revocada en el flujo 4 (VC_JWT)
VP_REVOKED=$(build_vp "$HOLDER_PRIV" "$HOLDER_DID" "$VC_JWT" "$BASE_URL")

# 6a. POST /credentials/verify — VP válida → 200 con valid=true
do_request "POST" "$BASE_URL/credentials/verify" "application/json" \
  "{\"vp_jwt\":\"$VP_VALID\"}"
log_request "POST /credentials/verify — VP válida" "POST" "$BASE_URL/credentials/verify"

assert_eq      "POST /credentials/verify → HTTP 200"            "200"    "$LAST_STATUS"
VALID_VAL=$(echo "$LAST_BODY" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('valid',''))" 2>/dev/null || echo "")
assert_eq      "VP válida: valid=true"                          "True"   "$VALID_VAL"
assert_contains "Respuesta contiene holder_did"                 "holder_did" "$LAST_BODY"
assert_contains "Respuesta contiene credentials"                "credentials" "$LAST_BODY"
assert_contains "Respuesta contiene UniversityDegreeCredential" "UniversityDegreeCredential" "$LAST_BODY"
log_section_end

# 6b. POST /credentials/verify — VP con VC revocada → 400
do_request "POST" "$BASE_URL/credentials/verify" "application/json" \
  "{\"vp_jwt\":\"$VP_REVOKED\"}"
log_request "POST /credentials/verify — VP con VC revocada" "POST" "$BASE_URL/credentials/verify"

assert_eq      "POST /credentials/verify con VC revocada → HTTP 400" "400"   "$LAST_STATUS"
VALID_VAL2=$(echo "$LAST_BODY" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('valid',''))" 2>/dev/null || echo "")
assert_eq      "VP con VC revocada: valid=false"                "False"  "$VALID_VAL2"
assert_contains "Respuesta contiene 'reason'"                   '"reason"' "$LAST_BODY"
log_section_end

# 6c. POST /credentials/verify — cuerpo sin vp_jwt → 400
do_request "POST" "$BASE_URL/credentials/verify" "application/json" "{}"
assert_eq "POST /credentials/verify sin vp_jwt → HTTP 400" "400" "$LAST_STATUS"
log_section_end
echo ""

# ─── Resultado final ──────────────────────────────────────────────────────────
TOTAL=$((PASS + FAIL))

{
  printf '\n---\n\n## Resultado final\n\n'
  if [ "$FAIL" -eq 0 ]; then
    printf '**✓ %d/%d tests pasaron**\n' "$PASS" "$TOTAL"
  else
    printf '**✗ %d fallos de %d tests**\n' "$FAIL" "$TOTAL"
  fi
} >> "$REPORT_FILE"

echo "══════════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
  green "RESULTADO: $PASS/$TOTAL tests pasaron"
else
  red   "RESULTADO: $FAIL fallos de $TOTAL tests"
fi
echo "══════════════════════════════════════════════"
echo ""
echo "  Reporte generado en:"
echo "  $REPORT_FILE"
echo ""

[ "$FAIL" -eq 0 ] || exit 1
