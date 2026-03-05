#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run-tests.sh — Ejecuta todas las pruebas usando Docker (no requiere Maven local)
#
# Fases:
#   1. Unit tests + pruebas de integración Spring Boot  (mvn verify via Docker Maven)
#   2. Pruebas E2E curl contra el backend local          (Docker Compose)
#   3. [opcional] Pruebas E2E curl contra Azure          (si se pasa URL o hay .azure-deploy-output.env)
#
# Uso:
#   ./scripts/run-tests.sh                    # fases 1 + 2
#   ./scripts/run-tests.sh unit               # solo fase 1 (más rápido)
#   ./scripts/run-tests.sh e2e                # solo fase 2 (backend levantado aparte)
#   ./scripts/run-tests.sh cloud              # solo fase 3 (lee APP_URL del .azure-deploy-output.env)
#   ./scripts/run-tests.sh cloud https://...  # fase 3 con URL explícita
#
# Requisitos: Docker
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$ROOT/backend"
DOCKER_DIR="$ROOT/docker"

MODE="${1:-all}"
CLOUD_URL="${2:-}"

# ─── Colores ──────────────────────────────────────────────────────────────────
ok()    { echo -e "\033[32m  ✓ $*\033[0m"; }
fail()  { echo -e "\033[31m  ✗ $*\033[0m"; }
step()  { echo -e "\n\033[34m▶ $*\033[0m"; }
die()   { echo -e "\033[31mERROR: $*\033[0m" >&2; exit 1; }

# ─── Prerequisitos ────────────────────────────────────────────────────────────
command -v docker >/dev/null || die "Docker no encontrado. Instalar: https://docs.docker.com/get-docker/"
docker info >/dev/null 2>&1  || die "Docker no está corriendo. Inicialo primero."

PASS=0
FAIL=0

phase_ok()   { PASS=$((PASS+1)); ok "$*"; }
phase_fail() { FAIL=$((FAIL+1)); fail "$*"; }

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  DID Issuer — Pruebas con Docker"
echo "  Modo: $MODE"
echo "═══════════════════════════════════════════════════════════════"

# ═════════════════════════════════════════════════════════════════════════════
# FASE 1: Unit tests + Spring Boot integration tests via Maven Docker
# ═════════════════════════════════════════════════════════════════════════════
run_unit_tests() {
  step "FASE 1 — Unit tests + Spring Boot integration tests (Maven en Docker)"

  # Montar caché ~/.m2 para no re-descargar dependencias en cada ejecución
  M2_CACHE="$HOME/.m2"
  mkdir -p "$M2_CACHE"

  # Copiar reportes generados por el test Java al directorio reports del proyecto
  REPORTS_HOST="$ROOT/reports"
  mkdir -p "$REPORTS_HOST"

  echo "  Imagen: maven:3.9-eclipse-temurin-17"
  echo "  Comando: mvn verify (unit + integration tests)"
  echo ""

  if docker run --rm \
    -v "$BACKEND_DIR:/app" \
    -v "$M2_CACHE:/root/.m2" \
    -v "$REPORTS_HOST:/app/target/reports" \
    -w /app \
    maven:3.9-eclipse-temurin-17 \
    mvn verify --no-transfer-progress; then
    phase_ok "mvn verify — todos los tests pasaron"
  else
    phase_fail "mvn verify — algunos tests fallaron (ver salida arriba)"
    return 1
  fi
}

# ═════════════════════════════════════════════════════════════════════════════
# FASE 2: E2E curl tests contra backend local (Docker Compose)
# ═════════════════════════════════════════════════════════════════════════════
run_local_e2e() {
  step "FASE 2 — Pruebas E2E curl contra backend local (Docker Compose)"

  # Levantar backend con docker compose (build incluido)
  echo "  Levantando backend..."
  docker compose -f "$DOCKER_DIR/docker-compose.yml" up -d --build --wait 2>&1 \
    | grep -v "^#" || true

  # Esperar health check (docker compose --wait ya lo hace, pero por si acaso)
  echo "  Esperando health check..."
  local retries=0
  until curl -sf --max-time 5 "http://localhost:8080/issuer/did" >/dev/null 2>&1; do
    retries=$((retries+1))
    [ $retries -gt 30 ] && { docker compose -f "$DOCKER_DIR/docker-compose.yml" down; die "Backend no arrancó"; }
    sleep 3
  done
  ok "Backend local disponible en http://localhost:8080"

  # Ejecutar script de pruebas curl
  local exit_code=0
  bash "$SCRIPT_DIR/test-integration.sh" "http://localhost:8080" || exit_code=$?

  # Apagar backend
  echo ""
  echo "  Apagando backend local..."
  docker compose -f "$DOCKER_DIR/docker-compose.yml" down --volumes 2>/dev/null || true

  if [ $exit_code -eq 0 ]; then
    phase_ok "Pruebas E2E locales — todas pasaron"
  else
    phase_fail "Pruebas E2E locales — algunas fallaron (ver salida arriba)"
    return 1
  fi
}

# ═════════════════════════════════════════════════════════════════════════════
# FASE 3: E2E curl tests contra Azure
# ═════════════════════════════════════════════════════════════════════════════
run_cloud_e2e() {
  step "FASE 3 — Pruebas E2E curl contra Azure"

  # Resolver URL: argumento explícito o del archivo de outputs
  local app_url="$CLOUD_URL"
  if [ -z "$app_url" ]; then
    local output_file="$ROOT/.azure-deploy-output.env"
    if [ -f "$output_file" ]; then
      app_url=$(grep '^APP_URL=' "$output_file" | cut -d= -f2- | tr -d '[:space:]')
    fi
  fi

  [ -z "$app_url" ] && die "No se encontró APP_URL. Pásala como argumento o ejecuta deploy-azure.sh primero."

  echo "  URL: $app_url"

  local exit_code=0
  bash "$SCRIPT_DIR/test-integration.sh" "$app_url" || exit_code=$?

  if [ $exit_code -eq 0 ]; then
    phase_ok "Pruebas E2E cloud ($app_url) — todas pasaron"
  else
    phase_fail "Pruebas E2E cloud ($app_url) — algunas fallaron (ver salida arriba)"
    return 1
  fi
}

# ─── Limpieza ante Ctrl+C ────────────────────────────────────────────────────
cleanup() {
  echo ""
  echo "  Interrumpido. Apagando Docker Compose si estaba corriendo..."
  docker compose -f "$DOCKER_DIR/docker-compose.yml" down --volumes 2>/dev/null || true
}
trap cleanup INT TERM

# ─── Ejecutar fases según MODE ────────────────────────────────────────────────
overall_exit=0

case "$MODE" in
  unit)
    run_unit_tests || overall_exit=1
    ;;
  e2e)
    run_local_e2e || overall_exit=1
    ;;
  cloud)
    run_cloud_e2e || overall_exit=1
    ;;
  all)
    run_unit_tests  || overall_exit=1
    run_local_e2e   || overall_exit=1
    ;;
  *)
    die "Modo desconocido: '$MODE'. Usa: all | unit | e2e | cloud"
    ;;
esac

# ─── Resumen ─────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
TOTAL=$((PASS + FAIL))
if [ $overall_exit -eq 0 ]; then
  echo -e "\033[32m  RESULTADO: $PASS/$TOTAL fases pasaron\033[0m"
else
  echo -e "\033[31m  RESULTADO: $FAIL fases fallaron de $TOTAL\033[0m"
fi
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  Reportes generados en: $ROOT/reports/"
echo ""

exit $overall_exit
