#!/usr/bin/env bash
set -euo pipefail

# ── Colors ──
BOLD="\033[1m"
DIM="\033[2m"
RESET="\033[0m"
CYAN="\033[0;36m"
GREEN="\033[0;32m"
YELLOW="\033[1;33m"
RED="\033[0;31m"
BRIGHT_CYAN="\033[1;36m"

# ── Status helpers ──
info()    { printf "  ${CYAN}[i]${RESET} %s\n" "$*"; }
success() { printf "  ${GREEN}[✔]${RESET} %s\n" "$*"; }
warn()    { printf "  ${YELLOW}[!]${RESET} %s\n" "$*"; }
error()   { printf "  ${RED}[✘]${RESET} %s\n" "$*"; }
header()  { printf "\n${BOLD}${CYAN}── %s${RESET}\n\n" "$*"; }

# ── Prompt helpers ──
prompt_value() {
  local prompt="$1" default="$2" value
  printf "  %s [${DIM}%s${RESET}]: " "$prompt" "$default" >&2
  read -r value
  echo "${value:-$default}"
}

prompt_yes_no() {
  local prompt="$1" default="${2:-y}" value
  if [[ "$default" == "y" ]]; then
    printf "  %s [${DIM}Y/n${RESET}]: " "$prompt"
  else
    printf "  %s [${DIM}y/N${RESET}]: " "$prompt"
  fi
  read -r value
  value="${value:-$default}"
  value=$(echo "$value" | tr '[:upper:]' '[:lower:]')
  [[ "$value" == "y" || "$value" == "yes" ]]
}

prompt_secret() {
  local prompt="$1" value
  printf "  %s: " "$prompt" >&2
  read -rs value
  echo >&2
  echo "$value"
}

# ── Spinner ──
spinner() {
  local pid=$1 msg="$2"
  local chars='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
  while kill -0 "$pid" 2>/dev/null; do
    for (( i=0; i<${#chars}; i++ )); do
      printf "\r  ${CYAN}%s${RESET} %s" "${chars:$i:1}" "$msg"
      sleep 0.1
    done
  done
  printf "\r\033[2K"
}

run_cmd() {
  local spinner_msg="$1" success_msg="$2"
  shift 2
  local cmdlog
  cmdlog=$(mktemp)
  "$@" >"$cmdlog" 2>&1 &
  local pid=$!
  spinner "$pid" "$spinner_msg"
  local rc=0
  wait "$pid" || rc=$?
  if [[ $rc -eq 0 ]]; then
    success "$success_msg"
  else
    error "$success_msg — failed (exit $rc)"
    if [[ -s "$cmdlog" ]]; then
      echo >&2
      while IFS= read -r line; do
        printf "  ${DIM}%s${RESET}\n" "$line" >&2
      done < "$cmdlog"
      echo >&2
    fi
    rm -f "$cmdlog"
    exit 1
  fi
  rm -f "$cmdlog"
}

# ── Logo ──
LOGO_LINES=7

print_logo() {
  local c="${1:-$CYAN}"
  printf "%b                                                               ░██    %b\n" "$c" "$RESET"
  printf "%b                                                               ░██    %b\n" "$c" "$RESET"
  printf "%b░█████████████   ░███████  ░████████   ░███████   ░██████   ░████████ %b\n" "$c" "$RESET"
  printf "%b░██   ░██   ░██ ░██    ░██ ░██    ░██ ░██    ░██       ░██     ░██    %b\n" "$c" "$RESET"
  printf "%b░██   ░██   ░██ ░██    ░██ ░██    ░██ ░█████████  ░███████     ░██    %b\n" "$c" "$RESET"
  printf "%b░██   ░██   ░██ ░██    ░██ ░██    ░██ ░██        ░██   ░██     ░██    %b\n" "$c" "$RESET"
  printf "%b░██   ░██   ░██  ░███████  ░██    ░██  ░███████   ░█████░██     ░████ %b\n" "$c" "$RESET"
}

pulse_logo() {
  print_logo "$CYAN"
  local colors=("$BRIGHT_CYAN" "${BOLD}${CYAN}" "$BRIGHT_CYAN" "$CYAN" "${DIM}${CYAN}")
  for cycle in 1 2; do
    for c in "${colors[@]}"; do
      printf "\033[${LOGO_LINES}A"
      print_logo "$c"
      sleep 0.07
    done
  done
}

# ── HTTP fetch helper ──
fetch_url() {
  local url="$1" dest="${2:-}"
  if command -v curl &>/dev/null; then
    if [[ -n "$dest" ]]; then
      curl -fsSL "$url" -o "$dest"
    else
      curl -fsSL "$url"
    fi
  elif command -v wget &>/dev/null; then
    if [[ -n "$dest" ]]; then
      wget -q "$url" -O "$dest"
    else
      wget -q "$url" -O -
    fi
  else
    error "curl or wget is required"
    exit 1
  fi
}

# ── Version selection ──
GITHUB_REPO="moneat-io/moneat"

prompt_version() {
  header "Version"

  local latest=""
  latest=$(fetch_url "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" "" 2>/dev/null \
    | grep '"tag_name"' | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/') || true

  if [[ -z "$latest" ]]; then
    latest="main"
    warn "Could not fetch latest release — defaulting to main branch"
  else
    info "Latest release: ${latest}"
  fi

  MONEAT_VERSION=$(prompt_value "Version to install" "$latest")
  success "Installing version: ${MONEAT_VERSION}"
}

# ── Setup install directory & download compose file ──
setup_install_dir() {
  header "Downloading"

  local install_dir="moneat"
  if [[ -f "docker-compose.yml" ]]; then
    info "docker-compose.yml already exists in current directory"
    INSTALL_DIR="$(pwd)"
  else
    mkdir -p "$install_dir"
    cd "$install_dir"
    INSTALL_DIR="$(pwd)"
    info "Created directory: ${INSTALL_DIR}"
  fi

  local base_url="https://raw.githubusercontent.com/${GITHUB_REPO}/${MONEAT_VERSION}"

  local tmp="docker-compose.yml.tmp"
  if ! fetch_url "${base_url}/docker-compose.yml" "$tmp"; then
    rm -f "$tmp"
    error "Failed to download docker-compose.yml"
    exit 1
  fi
  if [[ -f "docker-compose.yml" ]]; then
    local backup="docker-compose.yml.bak.$(date +%Y%m%d%H%M%S)"
    info "Existing docker-compose.yml found — backing up to ${backup}"
    mv "docker-compose.yml" "$backup"
  fi
  mv "$tmp" "docker-compose.yml"
  success "Downloaded docker-compose.yml"

  local files=(
    "backend/src/main/resources/db/init.sql"
    "backend/src/main/resources/db/clickhouse_init.sql"
    "clickhouse-config/logging.xml"
  )
  for f in "${files[@]}"; do
    mkdir -p "$(dirname "$f")"
    if fetch_url "${base_url}/${f}" "$f" 2>/dev/null; then
      success "Downloaded ${f##*/}"
    else
      warn "Could not download ${f} — container may handle init internally"
    fi
  done
}

# ── Port allocation ──
is_port_free() {
  ! (echo >/dev/tcp/localhost/"$1") 2>/dev/null
}

find_free_port() {
  local port=$1
  while ! is_port_free "$port"; do
    ((port++))
  done
  echo "$port"
}

allocate_ports() {
  header "Allocating Ports"

  BACKEND_PORT=$(find_free_port 8080)
  FRONTEND_PORT=$(find_free_port 3000)
  POSTGRES_PORT=$(find_free_port 5499)
  CLICKHOUSE_HTTP_PORT=$(find_free_port 8123)
  CLICKHOUSE_NATIVE_PORT=$(find_free_port 9000)
  REDIS_PORT=$(find_free_port 6379)
  TEMPORAL_GRPC_PORT=$(find_free_port 7233)
  TEMPORAL_UI_PORT=$(find_free_port 8233)

  success "Backend API        → :${BACKEND_PORT}"
  success "Frontend Dashboard → :${FRONTEND_PORT}"
  success "PostgreSQL         → :${POSTGRES_PORT}"
  success "ClickHouse HTTP    → :${CLICKHOUSE_HTTP_PORT}"
  success "ClickHouse Native  → :${CLICKHOUSE_NATIVE_PORT}"
  success "Redis              → :${REDIS_PORT}"
  success "Temporal gRPC      → :${TEMPORAL_GRPC_PORT}"
  success "Temporal UI        → :${TEMPORAL_UI_PORT}"
}

# ── Prerequisites ──
check_prerequisites() {
  header "Checking Prerequisites"

  local ok=true

  if command -v docker &>/dev/null; then
    success "Docker $(docker --version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')"
  else
    error "Docker is not installed — https://docs.docker.com/get-docker/"
    ok=false
  fi

  if docker compose version &>/dev/null; then
    success "Docker Compose $(docker compose version --short)"
  else
    error "Docker Compose v2 is required — https://docs.docker.com/compose/install/"
    ok=false
  fi

  if command -v openssl &>/dev/null; then
    success "OpenSSL $(openssl version | awk '{print $2}')"
  else
    error "OpenSSL is required for secret generation"
    ok=false
  fi

  if command -v curl &>/dev/null; then
    success "curl $(curl --version | head -1 | awk '{print $2}')"
  elif command -v wget &>/dev/null; then
    success "wget $(wget --version 2>&1 | head -1 | awk '{print $3}')"
  else
    error "curl or wget is required — install one and try again"
    ok=false
  fi

  if [[ "$ok" == false ]]; then
    echo
    error "Missing prerequisites. Please install them and try again."
    exit 1
  fi
}

# ── Configuration prompts ──
prompt_domain() {
  header "Configuration"

  DOMAIN=$(prompt_value "Domain or IP for this instance" "localhost")

  if [[ "$DOMAIN" == "localhost" || "$DOMAIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    PROTOCOL="http"
  else
    PROTOCOL="https"
  fi

  BACKEND_URL="${PROTOCOL}://${DOMAIN}:${BACKEND_PORT}"
  FRONTEND_URL="${PROTOCOL}://${DOMAIN}:${FRONTEND_PORT}"

  info "Backend URL:  ${BACKEND_URL}"
  info "Frontend URL: ${FRONTEND_URL}"
}

prompt_smtp() {
  echo
  if prompt_yes_no "Configure SMTP for email notifications?" "n"; then
    SMTP_HOST=$(prompt_value "SMTP host" "smtp.gmail.com")
    SMTP_PORT=$(prompt_value "SMTP port" "587")
    SMTP_USERNAME=$(prompt_value "SMTP username" "")
    SMTP_PASSWORD=$(prompt_secret "SMTP password")
    EMAIL_FROM=$(prompt_value "From address" "noreply@${DOMAIN}")
    SMTP_CONFIGURED=true
  else
    SMTP_HOST=""
    SMTP_PORT=""
    SMTP_USERNAME=""
    SMTP_PASSWORD=""
    EMAIL_FROM=""
    SMTP_CONFIGURED=false
    warn "Skipping SMTP — email verification will be disabled"
  fi
}

prompt_telemetry() {
  echo
  if prompt_yes_no "Enable anonymous telemetry to help improve Moneat?" "y"; then
    TELEMETRY_ENABLED=true
    success "Telemetry enabled (anonymous usage stats only)"
  else
    TELEMETRY_ENABLED=false
    info "Telemetry disabled"
  fi
}

prompt_license() {
  echo
  MONEAT_LICENSE_KEY=$(prompt_value "Enterprise license key (leave blank to skip)" "")
  if [[ -n "$MONEAT_LICENSE_KEY" ]]; then
    success "License key set"
  else
    info "No license key — using open-source core"
  fi
}

# ── Secret generation ──
generate_secrets() {
  header "Generating Secrets"

  info "Method: openssl rand -base64 N | tr -d '/+='"
  echo

  JWT_SECRET=$(openssl rand -base64 64 | tr -d '/+=\n' | head -c 64)
  success "JWT_SECRET             (64 chars, alphanumeric)"

  DATABASE_PASSWORD=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "DATABASE_PASSWORD      (32 chars, alphanumeric)"

  CLICKHOUSE_PASSWORD=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "CLICKHOUSE_PASSWORD    (32 chars, alphanumeric)"

  REDIS_PASSWORD=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "REDIS_PASSWORD         (32 chars, alphanumeric)"

  DATA_SOURCE_ENCRYPTION_KEY=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "DATA_SOURCE_ENCRYPTION_KEY (32 chars, alphanumeric)"

  TEMPORAL_DB_PASSWORD=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "TEMPORAL_DB_PASSWORD (32 chars, alphanumeric)"

  WORKFLOWS_CONNECTION_KEK=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "WORKFLOWS_CONNECTION_KEK (32 chars, alphanumeric)"

  WORKFLOWS_SIGNING_KEY=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "WORKFLOWS_SIGNING_KEY (32 chars, alphanumeric)"

  WORKFLOWS_TEMPORAL_PAYLOAD_KEY=$(openssl rand -base64 32 | tr -d '/+=\n' | head -c 32)
  success "WORKFLOWS_TEMPORAL_PAYLOAD_KEY (32 chars, alphanumeric)"
}

# ── Write .env files ──
write_env_file() {
  header "Writing Configuration"

  if [[ -f .env ]]; then
    cp .env ".env.backup.$(date +%s)"
    warn "Backed up existing .env"
  fi
  if [[ -f dashboard/.env ]]; then
    cp dashboard/.env "dashboard/.env.backup.$(date +%s)"
    warn "Backed up existing dashboard/.env"
  fi

  local disable_email="true"
  if [[ "$SMTP_CONFIGURED" == true ]]; then
    disable_email="false"
  fi

  cat > .env <<ENVFILE
# Generated by install.sh on $(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Server
PORT=${BACKEND_PORT}
BACKEND_URL=${BACKEND_URL}
FRONTEND_URL=${FRONTEND_URL}
ALLOWED_ORIGINS=

# PostgreSQL
DATABASE_URL=jdbc:postgresql://localhost:${POSTGRES_PORT}/moneat
DATABASE_USER=moneat
DATABASE_PASSWORD=${DATABASE_PASSWORD}

# ClickHouse
CLICKHOUSE_URL=http://localhost:${CLICKHOUSE_HTTP_PORT}
CLICKHOUSE_USER=moneat
CLICKHOUSE_PASSWORD=${CLICKHOUSE_PASSWORD}

# Redis
REDIS_URL=redis://:${REDIS_PASSWORD}@localhost:${REDIS_PORT}

# Workflow runtime
TEMPORAL_TARGET=temporal:7233
TEMPORAL_NAMESPACE=default
TEMPORAL_DB_USER=temporal
TEMPORAL_DB_PASSWORD=${TEMPORAL_DB_PASSWORD}
WORKFLOWS_CONNECTION_KEK=${WORKFLOWS_CONNECTION_KEK}
WORKFLOWS_SIGNING_KEY=${WORKFLOWS_SIGNING_KEY}
WORKFLOWS_TEMPORAL_PAYLOAD_KEY=${WORKFLOWS_TEMPORAL_PAYLOAD_KEY}

# JWT
JWT_SECRET=${JWT_SECRET}

# Custom data source credential encryption (separate from JWT)
DATA_SOURCE_ENCRYPTION_KEY=${DATA_SOURCE_ENCRYPTION_KEY}

# Email / SMTP
EMAIL_FROM=${EMAIL_FROM}
SMTP_HOST=${SMTP_HOST}
SMTP_PORT=${SMTP_PORT}
SMTP_USERNAME=${SMTP_USERNAME}
SMTP_PASSWORD=${SMTP_PASSWORD}
SMTP_AUTH=true
SMTP_STARTTLS=true
SELF_HOSTED=true
DISABLE_EMAIL_VERIFICATION=${disable_email}

# Stripe (disabled for self-hosted)
STRIPE_ENABLED=false

# Telemetry
TELEMETRY_ENABLED=${TELEMETRY_ENABLED}

# Enterprise License
MONEAT_LICENSE_KEY=${MONEAT_LICENSE_KEY}

# Docker Compose port overrides
BACKEND_PORT=${BACKEND_PORT}
FRONTEND_PORT=${FRONTEND_PORT}
POSTGRES_PORT=${POSTGRES_PORT}
CLICKHOUSE_HTTP_PORT=${CLICKHOUSE_HTTP_PORT}
CLICKHOUSE_NATIVE_PORT=${CLICKHOUSE_NATIVE_PORT}
REDIS_PORT=${REDIS_PORT}
TEMPORAL_GRPC_PORT=${TEMPORAL_GRPC_PORT}
TEMPORAL_UI_PORT=${TEMPORAL_UI_PORT}
ENVFILE

  success "Created .env"
}

# ── Build & Start ──
build_and_start() {
  header "Starting Services"

  info "Pulling images..."
  run_cmd "Pulling Docker images..." "Images pulled" \
    docker compose pull --quiet

  info "Starting databases..."
  run_cmd "Starting PostgreSQL, ClickHouse, Redis, and Temporal..." "Databases started" \
    docker compose up -d postgres clickhouse redis temporal-bootstrap temporal temporal-ui

  info "Waiting for databases to be healthy..."
  local retries=30
  while [[ $retries -gt 0 ]]; do
    local healthy=0
    for svc in moneat-postgres moneat-clickhouse moneat-redis moneat-temporal; do
      if docker inspect --format='{{.State.Health.Status}}' "$svc" 2>/dev/null | grep -q healthy; then
        healthy=$((healthy + 1))
      fi
    done
    [[ $healthy -ge 4 ]] && break
    sleep 2
    retries=$((retries - 1))
  done

  if [[ $retries -eq 0 ]]; then
    warn "Database health check timed out — continuing anyway"
  else
    success "Databases healthy"
  fi

  info "Starting application..."
  run_cmd "Starting backend and frontend..." "Application started" \
    docker compose up -d backend frontend
}

# ── Health check ──
health_check() {
  header "Health Check"

  local backend_url="http://localhost:${BACKEND_PORT}/health"
  local frontend_url="http://localhost:${FRONTEND_PORT}"
  local retries=30
  local backend_ok=false
  local frontend_ok=false

  info "Waiting for services to be ready..."

  while [[ $retries -gt 0 ]]; do
    if [[ "$backend_ok" == false ]] && fetch_url "$backend_url" "" &>/dev/null; then
      backend_ok=true
      success "Backend is healthy"
    fi
    if [[ "$frontend_ok" == false ]] && fetch_url "$frontend_url" "" &>/dev/null; then
      frontend_ok=true
      success "Frontend is healthy"
    fi
    [[ "$backend_ok" == true && "$frontend_ok" == true ]] && break
    sleep 2
    retries=$((retries - 1))
  done

  [[ "$backend_ok" == false ]] && warn "Backend did not respond — check: docker compose logs backend"
  [[ "$frontend_ok" == false ]] && warn "Frontend did not respond — check: docker compose logs frontend"
}

# ── Summary ──
print_summary() {
  header "Setup Complete"

  printf "  ${GREEN}${BOLD}Moneat is running!${RESET}\n\n"

  printf "  ${BOLD}Version:${RESET}    %s\n" "${MONEAT_VERSION}"
  printf "  ${BOLD}Directory:${RESET}  %s\n" "${INSTALL_DIR}"
  printf "  ${BOLD}Dashboard:${RESET}  %s\n" "${FRONTEND_URL}"
  printf "  ${BOLD}API:${RESET}        %s\n" "${BACKEND_URL}"
  printf "  ${BOLD}Temporal UI:${RESET} http://%s:%s\n" "${DOMAIN:-localhost}" "${TEMPORAL_UI_PORT}"
  echo

  if [[ "$SMTP_CONFIGURED" == false ]]; then
    warn "Email is disabled — the first registered user will be auto-verified as admin"
  fi

  echo
  info "Useful commands:"
  printf "    ${DIM}docker compose logs -f${RESET}                            # follow logs\n"
  printf "    ${DIM}docker compose ps${RESET}                                 # service status\n"
  printf "    ${DIM}docker compose down${RESET}                               # stop services\n"
  printf "    ${DIM}docker compose pull && docker compose up -d${RESET}       # update\n"
  echo
}

# ── Main ──
main() {
  pulse_logo
  printf "\n"
  printf "  ${DIM}Interactive installer for self-hosted Moneat${RESET}\n"
  printf "  ${DIM}https://moneat.io/docs/self-hosting${RESET}\n\n"

  check_prerequisites
  prompt_version
  setup_install_dir
  allocate_ports
  prompt_domain
  prompt_smtp
  prompt_telemetry
  prompt_license
  generate_secrets
  write_env_file
  build_and_start
  health_check
  print_summary
}

main "$@"
