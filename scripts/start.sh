#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start.sh — Levanta el backend del DID Issuer
#
# Uso:
#   ./scripts/start.sh            # modo local (requiere Java 17 + Maven)
#   ./scripts/start.sh docker     # modo Docker (requiere Docker)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-local}"

green()  { echo -e "\033[32m$*\033[0m"; }
yellow() { echo -e "\033[33m$*\033[0m"; }
red()    { echo -e "\033[31m$*\033[0m"; }

case "$MODE" in

  local)
    green "▶ Iniciando backend en modo local..."
    command -v java  >/dev/null || { red "Java 17+ requerido"; exit 1; }
    command -v mvn   >/dev/null || { red "Maven 3.9+ requerido"; exit 1; }

    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
      red "Se requiere Java 17+. Versión actual: $JAVA_VERSION"
      exit 1
    fi

    green "→ http://localhost:8080"
    cd "$ROOT/backend" && mvn spring-boot:run
    ;;

  docker)
    green "▶ Iniciando backend en modo Docker..."
    command -v docker >/dev/null || { red "Docker requerido"; exit 1; }

    cd "$ROOT"
    docker compose -f docker/docker-compose.yml up --build

    green "→ Backend disponible en http://localhost:8080"
    ;;

  stop)
    yellow "■ Deteniendo contenedores..."
    cd "$ROOT"
    docker compose -f docker/docker-compose.yml down
    ;;

  *)
    echo "Uso: $0 [local|docker|stop]"
    exit 1
    ;;
esac
