#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test-integration.sh — Pruebas de integración end-to-end via curl
#
# Replica los tres flujos DID contra el backend en ejecución.
# Genera un reporte Markdown con cada request y response.
# Requiere: curl, jq, python3, pip3
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

# Estado interno de do_request
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
# do_request METHOD URL [CONTENT_TYPE] [BODY]
# Resultado en LAST_STATUS (código HTTP) y LAST_BODY (cuerpo de respuesta)
do_request() {
  local method="$1" url="$2" content_type="${3:-}" body="${4:-}"
  local curl_args=(-s -X "$method")
  [ -n "$content_type" ] && curl_args+=(-H "Content-Type: $content_type")
  [ -n "$body" ]         && curl_args+=(-d "$body")

  local tmp
  tmp=$(mktemp)
  LAST_STATUS=$(curl "${curl_args[@]}" -o "$tmp" -w "%{http_code}" "$url" 2>/dev/null || echo "000")
  LAST_BODY=$(cat "$tmp")
  rm -f "$tmp"
}

# ─── Formateo JSON ────────────────────────────────────────────────────────────
pretty_json() {
  echo "$1" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin), indent=2, ensure_ascii=False))" 2>/dev/null \
    || echo "$1"
}

# Decodifica header y payload de un JWT para mostrarlo legible
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

# log_request TITLE METHOD URL [REQ_DISPLAY]
# Usa LAST_STATUS y LAST_BODY para la respuesta
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

# ─── Generador de Proof JWT (Python) ─────────────────────────────────────────
generate_proof() {
  local nonce="$1" issuer_url="$2" credential_type="${3:-VerifiableCredential}"

  python3 - "$nonce" "$issuer_url" "$credential_type" <<'PYEOF'
import sys, json, base64, hashlib, time

try:
    from cryptography.hazmat.primitives.asymmetric.ec import (
        generate_private_key, SECP256K1, ECDSA)
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PublicFormat)
    from cryptography.hazmat.primitives.asymmetric.utils import (
        decode_dss_signature)
except ImportError:
    print(json.dumps({"error": "pip install cryptography"}))
    sys.exit(1)

nonce           = sys.argv[1]
issuer_url      = sys.argv[2]
credential_type = sys.argv[3]

ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58encode(data: bytes) -> str:
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

priv_key = generate_private_key(SECP256K1(), default_backend())
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
der_sig = priv_key.sign(signing_input.encode(), ECDSA(hashes.SHA256()))
r, s    = decode_dss_signature(der_sig)
sig_bytes = r.to_bytes(32, "big") + s.to_bytes(32, "big")
jwt       = signing_input + "." + b64url(sig_bytes)

print(json.dumps({
    "did":            did,
    "public_key_hex": pub_bytes.hex(),
    "proof_jwt":      jwt
}))
PYEOF
}

# ─── Esperar backend ──────────────────────────────────────────────────────────
wait_for_backend() {
  blue "Esperando al backend en $BASE_URL..."
  for i in $(seq 1 20); do
    if curl -sf "$BASE_URL/issuer/did" >/dev/null 2>&1; then
      green "Backend disponible"
      return 0
    fi
    sleep 2
    echo -n "."
  done
  red "Backend no responde en $BASE_URL"
  exit 1
}

# ═════════════════════════════════════════════════════════════════════════════
# INICIO
# ═════════════════════════════════════════════════════════════════════════════

check_deps

# Cabecera del reporte
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

# ─── Flujo 1: Identidad del Issuer ───────────────────────────────────────────
blue "FLUJO 1 — Identidad del Issuer"
divider
report_section "Flujo 1 — Identidad del Issuer"

# 1a. GET /issuer/did
do_request "GET" "$BASE_URL/issuer/did"
log_request "GET /issuer/did" "GET" "$BASE_URL/issuer/did"

assert_eq      "GET /issuer/did → HTTP 200"                "200"             "$LAST_STATUS"
assert_contains "Respuesta contiene 'did'"                 '"did"'           "$LAST_BODY"
assert_contains "Respuesta contiene 'public_key_hex'"      '"public_key_hex"' "$LAST_BODY"

ISSUER_DID=$(echo "$LAST_BODY" | jq -r '.did')
assert_contains "DID comienza con did:key:z"               "did:key:z"       "$ISSUER_DID"

PUB_HEX=$(echo "$LAST_BODY" | jq -r '.public_key_hex')
assert_eq       "Clave pública = 66 hex chars (33 bytes comprimidos)" "66"   "${#PUB_HEX}"

yellow "Issuer DID: $ISSUER_DID"
log_section_end

# 1b. GET /issuer/did-document
do_request "GET" "$BASE_URL/issuer/did-document"
log_request "GET /issuer/did-document" "GET" "$BASE_URL/issuer/did-document"

assert_eq      "GET /issuer/did-document → HTTP 200"           "200"                  "$LAST_STATUS"
assert_contains "DID Document contiene @context"               '@context'             "$LAST_BODY"
assert_contains "DID Document contiene verificationMethod"     'verificationMethod'   "$LAST_BODY"
assert_contains "DID Document contiene EcdsaSecp256k1"         'EcdsaSecp256k1'       "$LAST_BODY"

log_section_end
echo ""

# ─── Flujo 2: Obtención de Verifiable Credential ─────────────────────────────
blue "FLUJO 2 — Obtención de Verifiable Credential"
divider
report_section "Flujo 2 — Obtención de Verifiable Credential"

# 2a. GET /credentials/nonce
do_request "GET" "$BASE_URL/credentials/nonce"
log_request "GET /credentials/nonce" "GET" "$BASE_URL/credentials/nonce"

assert_eq      "GET /credentials/nonce → HTTP 200"  "200"    "$LAST_STATUS"
assert_contains "Respuesta contiene 'nonce'"         '"nonce"' "$LAST_BODY"

NONCE=$(echo "$LAST_BODY" | jq -r '.nonce')
assert_contains "Nonce no está vacío"                '.'      "$NONCE"
yellow "Nonce obtenido: $NONCE"
log_section_end

# 2b. Generar par de claves + proof JWT firmado
yellow "Generando par secp256k1 y firmando Proof JWT..."
PROOF_DATA=$(generate_proof "$NONCE" "$BASE_URL" "UniversityDegreeCredential")

HOLDER_DID=$(echo "$PROOF_DATA" | jq -r '.did')
PROOF_JWT=$(echo "$PROOF_DATA"  | jq -r '.proof_jwt')
yellow "Holder DID: $HOLDER_DID"

# 2c. POST /credentials/issue
ISSUE_BODY="{\"holder_did\":\"$HOLDER_DID\",\"proof\":\"$PROOF_JWT\"}"
do_request "POST" "$BASE_URL/credentials/issue" "application/json" "$ISSUE_BODY"

# Construir display del request con proof JWT decodificado
DECODED_PROOF=$(decode_jwt_display "$PROOF_JWT")
ISSUE_DISPLAY=$(python3 - "$HOLDER_DID" "$DECODED_PROOF" <<'PYEOF'
import sys, json
holder_did    = sys.argv[1]
decoded_proof = json.loads(sys.argv[2])
print(json.dumps({"holder_did": holder_did, "proof": decoded_proof}, indent=2, ensure_ascii=False))
PYEOF
)

# Construir display de la respuesta con VC JWT decodificada
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

assert_eq      "POST /credentials/issue → HTTP 200"                    "200"                        "$ISSUE_STATUS"
assert_contains "Respuesta contiene 'credential'"                      '"credential"'               "$ISSUE_BODY_RESP"

VC_JWT=$(echo "$ISSUE_BODY_RESP" | jq -r '.credential // empty')
VC_PARTS=$(echo "$VC_JWT" | tr '.' '\n' | wc -l | tr -d ' ')
assert_eq      "VC JWT tiene 3 partes (header.payload.signature)"      "3"                          "$VC_PARTS"

# Decodificar payload de la VC para validar su contenido
VC_PAYLOAD=$(python3 - "$VC_JWT" <<'PYEOF'
import sys, json, base64
jwt = sys.argv[1]
part = jwt.split(".")[1]
part += "=" * ((4 - len(part) % 4) % 4)
print(base64.urlsafe_b64decode(part).decode("utf-8"))
PYEOF
)
assert_contains "VC contiene el DID del holder como 'sub'"             "$HOLDER_DID"                "$VC_PAYLOAD"
assert_contains "VC contiene 'UniversityDegreeCredential'"             "UniversityDegreeCredential" "$VC_PAYLOAD"
assert_contains "VC contiene 'iss' (DID del issuer)"                   '"iss"'                      "$VC_PAYLOAD"
assert_contains "VC contiene 'credentialSubject'"                      "credentialSubject"          "$VC_PAYLOAD"
assert_contains "VC contiene claims del sujeto"                        "Juan"                       "$VC_PAYLOAD"

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

assert_eq "POST /credentials/issue con nonce repetido → HTTP 400" "400" "$LAST_STATUS"
log_section_end

# Nonce inventado
FAKE_PROOF_DATA=$(generate_proof "nonce-inventado-$(date +%s)" "$BASE_URL")
FAKE_PROOF=$(echo "$FAKE_PROOF_DATA" | jq -r '.proof_jwt')
FAKE_DID=$(echo "$FAKE_PROOF_DATA"   | jq -r '.did')

FAKE_DECODED=$(decode_jwt_display "$FAKE_PROOF")
FAKE_DISPLAY=$(python3 - "$FAKE_DID" "$FAKE_DECODED" <<'PYEOF'
import sys, json
print(json.dumps({"holder_did": sys.argv[1], "proof": json.loads(sys.argv[2])}, indent=2, ensure_ascii=False))
PYEOF
)

do_request "POST" "$BASE_URL/credentials/issue" "application/json" \
  "{\"holder_did\":\"$FAKE_DID\",\"proof\":\"$FAKE_PROOF\"}"

{
  printf '### POST /credentials/issue — nonce que nunca existió\n\n'
  printf '**Request**\n\n```\nPOST %s/credentials/issue\nContent-Type: application/json\n\n%s\n```\n\n' \
    "$BASE_URL" "$FAKE_DISPLAY"
  printf '**Response** — HTTP %s\n\n```json\n%s\n```\n\n' "$LAST_STATUS" "$(pretty_json "$LAST_BODY")"
  printf '**Aserciones:**\n\n'
} >> "$REPORT_FILE"

assert_eq "POST /credentials/issue con nonce inválido → HTTP 400" "400" "$LAST_STATUS"
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
