#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG=""
COMMAND=""
declare -a SECRETS=()
declare -a ROOM_PROVIDERS=()
declare -a ROOM_TRANSPORTS=()
declare -a ROOM_LABELS=()
declare -a ROOM_URLS=()
declare -a ROOM_KEYS=()

usage() {
  printf 'usage: %s --config FILE check|build|deploy|subscription|publish|status\n' "${0##*/}" >&2
  exit 2
}

redact() {
  local line secret
  while IFS= read -r line || [ -n "${line}" ]; do
    for secret in "${SECRETS[@]}"; do
      if [ -n "${secret}" ]; then
        line="${line//"${secret}"/[REDACTED]}"
      fi
    done
    printf '%s\n' "${line}"
  done
}

die() {
  printf 'deploy-olcrtc-vds: %s\n' "$*" | redact >&2
  exit 1
}

ssh_run() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 "${SSH_TARGET}" "$@" 2> >(redact >&2) | redact
}

scp_upload() {
  scp -q "$1" "${SSH_TARGET}:$2" 2> >(redact >&2)
}

require_value() {
  local name="$1"
  if [ -z "${!name-}" ]; then
    die "missing required config field ${name}"
  fi
}

load_config() {
  [ -f "${CONFIG}" ] || die "config not found: ${CONFIG}"
  # shellcheck source=/dev/null
  . "${CONFIG}"

  local name provider index url key previous other port
  for name in SSH_TARGET ARTIFACT_DIR PUBLISH_PATH PUBLISH_URL VLESS_UUID VLESS_LISTEN OLCRTC_SOURCE XRAY_BINARY; do
    require_value "${name}"
  done
  [[ "${SSH_TARGET}" =~ ^root@[A-Za-z0-9._:-]+$ ]] || die "SSH_TARGET must be root@host"
  [[ "${ARTIFACT_DIR}" = /* && "${ARTIFACT_DIR}" != *[[:space:]]* ]] || die "ARTIFACT_DIR must be an absolute path without whitespace"
  [[ "${PUBLISH_PATH}" = /* && "${PUBLISH_PATH}" != *[[:space:]]* ]] || die "PUBLISH_PATH must be an absolute path without whitespace"
  [[ "${PUBLISH_URL}" = https://* ]] || die "PUBLISH_URL must use HTTPS"
  [[ "${VLESS_UUID}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || die "VLESS_UUID is not a UUID"
  [[ "${VLESS_LISTEN}" =~ ^(127\.0\.0\.1|\[::1\]):[0-9]+$ ]] || die "VLESS_LISTEN must use a loopback address"
  [[ "${OLCRTC_SOURCE}" = /* ]] || die "OLCRTC_SOURCE must be an absolute path"
  [[ "${XRAY_BINARY}" = /* ]] || die "XRAY_BINARY must be an absolute path"
  port="${VLESS_LISTEN##*:}"
  ((port >= 1 && port <= 65535)) || die "VLESS_LISTEN port is out of range"

  ROOM_PROVIDERS=()
  ROOM_TRANSPORTS=()
  ROOM_LABELS=()
  ROOM_URLS=()
  ROOM_KEYS=()
  SECRETS=("${VLESS_UUID}")
  for provider in MAMBA VKCALLS TELEMOST; do
    for index in 1 2 3 4; do
      name="${provider}_URL_${index}"
      require_value "${name}"
      url="${!name}"
      name="${provider}_KEY_${index}"
      require_value "${name}"
      key="${!name}"
      [[ "${url}" = https://* && "${url}" != *"'"* ]] || die "${provider}_URL_${index} must be an HTTPS URL without a single quote"
      [[ "${key}" =~ ^[0-9a-fA-F]{64}$ ]] || die "${provider}_KEY_${index} must be 64 hexadecimal characters"
      if [ "${provider}" = TELEMOST ]; then
        [[ "${url}" =~ ^https://telemost\.yandex\.ru/j/[A-Za-z0-9_-]+$ ]] ||
          die "TELEMOST_URL_${index} must be a permanent clean https://telemost.yandex.ru/j/ROOM URL"
      fi
      for previous in 1 2 3 4; do
        if ((previous < index)); then
          other="${provider}_URL_${previous}"
          [ "${url}" != "${!other}" ] || die "duplicate ${provider} room URL at indexes ${previous} and ${index}"
        fi
      done
      ROOM_PROVIDERS+=("${provider,,}")
      ROOM_LABELS+=("${provider:0:1}${index}")
      ROOM_URLS+=("${url}")
      ROOM_KEYS+=("${key}")
      if [ "${provider}" = MAMBA ]; then
        ROOM_TRANSPORTS+=("datachannel")
      else
        ROOM_TRANSPORTS+=("vp8channel")
      fi
      SECRETS+=("${url}" "${key}")
    done
  done
}

check_remote() {
  # shellcheck disable=SC2016
  ssh_run '
    printf "== OS ==\n"
    if [ -r /etc/os-release ]; then . /etc/os-release; printf "%s\n" "${PRETTY_NAME:-unknown}"; fi
    uname -m
    printf "== Memory ==\n"
    awk "/MemTotal|MemAvailable/ {print}" /proc/meminfo
    printf "== Disk ==\n"
    df -h /
    printf "== Listen ports ==\n"
    ss -ltn
    printf "== Existing IlyukhaLine units ==\n"
    systemctl list-unit-files "ilyukhaline-*" --no-legend 2>/dev/null || true
    printf "== Existing cores and VPN services ==\n"
    command -v olcrtc || true
    command -v xray || true
    systemctl list-units --all --type=service --no-legend 2>/dev/null |
      grep -Ei "ilyukhaline|olcrtc|xray|3x-ui|hysteria|amnezia" || true
  '
}

build_local() {
  [ -f "${OLCRTC_SOURCE}/magefile.go" ] || die "OLCRTC_SOURCE is not an olcRTC checkout"
  [ -x "${XRAY_BINARY}" ] || die "XRAY_BINARY is not executable"
  if [ -d "${OLCRTC_SOURCE}/.git" ]; then
    git -C "${OLCRTC_SOURCE}" diff --quiet || die "olcRTC source has uncommitted changes"
    git -C "${OLCRTC_SOURCE}" diff --cached --quiet || die "olcRTC source has staged changes"
  fi
  mkdir -p "${ARTIFACT_DIR}"
  (
    cd "${OLCRTC_SOURCE}"
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 mage build
  )
  [ -f "${OLCRTC_SOURCE}/dist/olcrtc" ] || die "local olcRTC build produced no binary"
  cp "${OLCRTC_SOURCE}/dist/olcrtc" "${ARTIFACT_DIR}/olcrtc-linux-amd64"
  cp "${XRAY_BINARY}" "${ARTIFACT_DIR}/xray"
  chmod 755 "${ARTIFACT_DIR}/olcrtc-linux-amd64" "${ARTIFACT_DIR}/xray"
  (
    cd "${ARTIFACT_DIR}"
    sha256sum olcrtc-linux-amd64 > olcrtc-linux-amd64.sha256
    sha256sum xray > xray.sha256
  )
  printf 'built local Linux amd64 olcRTC and staged Xray in %s\n' "${ARTIFACT_DIR}"
}

render_room() {
  local index="$1" engine_provider file
  case "${ROOM_PROVIDERS[$index]}" in
    mamba) engine_provider=jitsi ;;
    vkcalls) engine_provider=vkcalls ;;
    telemost) engine_provider=telemost ;;
    *) die "unknown provider ${ROOM_PROVIDERS[$index]}" ;;
  esac
  file="${ARTIFACT_DIR}/staging/${ROOM_LABELS[$index],,}.yaml"
  printf '%s\n' \
    'mode: srv' \
    'auth:' \
    "  provider: ${engine_provider}" \
    'room:' \
    "  id: '${ROOM_URLS[$index]}'" \
    'crypto:' \
    "  key: '${ROOM_KEYS[$index]}'" \
    'net:' \
    "  transport: ${ROOM_TRANSPORTS[$index]}" \
    '  dns: "1.1.1.1:53"' > "${file}"
}

render_deployment() {
  local index template host port
  rm -rf "${ARTIFACT_DIR}/staging"
  mkdir -p "${ARTIFACT_DIR}/staging"
  for index in "${!ROOM_LABELS[@]}"; do
    render_room "${index}"
  done
  host="${VLESS_LISTEN%:*}"
  host="${host#[}"
  host="${host%]}"
  port="${VLESS_LISTEN##*:}"
  template="$(<"${REPO_ROOT}/deploy/xray-vless.json.template")"
  template="${template//__VLESS_HOST__/${host}}"
  template="${template//__VLESS_PORT__/${port}}"
  template="${template//__VLESS_UUID__/${VLESS_UUID}}"
  printf '%s\n' "${template}" > "${ARTIFACT_DIR}/staging/xray.json"
  cp "${REPO_ROOT}/deploy/systemd/ilyukhaline-olcrtc@.service" "${ARTIFACT_DIR}/staging/"
  cp "${REPO_ROOT}/deploy/systemd/ilyukhaline-xray.service" "${ARTIFACT_DIR}/staging/"
  cp "${ARTIFACT_DIR}/olcrtc-linux-amd64" "${ARTIFACT_DIR}/staging/olcrtc"
  cp "${ARTIFACT_DIR}/xray" "${ARTIFACT_DIR}/staging/xray"
}

deploy_remote() {
  [ -x "${ARTIFACT_DIR}/olcrtc-linux-amd64" ] || die "run build first"
  [ -x "${ARTIFACT_DIR}/xray" ] || die "run build first"
  render_deployment
  local stage file name olcrtc_hash xray_hash
  stage="/tmp/ilyukhaline/deploy-$$"
  olcrtc_hash="$(awk '{print $1}' "${ARTIFACT_DIR}/olcrtc-linux-amd64.sha256")"
  xray_hash="$(awk '{print $1}' "${ARTIFACT_DIR}/xray.sha256")"
  ssh_run "mkdir -p '${stage}' && chmod 700 '${stage}'" >/dev/null
  for file in "${ARTIFACT_DIR}/staging"/*; do
    name="${file##*/}"
    scp_upload "${file}" "${stage}/${name}"
  done
  ssh_run bash -s "${stage}" "${olcrtc_hash}" "${xray_hash}" < "${REPO_ROOT}/deploy/remote-deploy.sh"
}

status_remote() {
  local index label state xray_state
  printf 'VLESS %s\n' "${VLESS_LISTEN}"
  for index in "${!ROOM_LABELS[@]}"; do
    label="${ROOM_LABELS[$index]}"
    state="$(ssh_run systemctl is-active "ilyukhaline-olcrtc@${label,,}.service" || true)"
    printf '%s %s %s\n' "${label}" "${ROOM_PROVIDERS[$index]}" "${state:-unknown}"
  done
  xray_state="$(ssh_run systemctl is-active ilyukhaline-xray.service || true)"
  printf 'Xray %s health=%s\n' "${xray_state:-unknown}" "$(ssh_run /opt/ilyukhaline/bin/xray run -test -config /etc/ilyukhaline/xray.json >/dev/null && printf ok || printf failed)"
}

while (($#)); do
  case "$1" in
    --config)
      (($# >= 2)) || usage
      CONFIG="$2"
      shift 2
      ;;
    check|build|deploy|subscription|publish|status)
      [ -z "${COMMAND}" ] || usage
      COMMAND="$1"
      shift
      ;;
    *) usage ;;
  esac
done
[ -n "${CONFIG}" ] && [ -n "${COMMAND}" ] || usage

load_config
case "${COMMAND}" in
  check) check_remote ;;
  build) build_local ;;
  deploy) deploy_remote ;;
  status) status_remote ;;
  *) die "${COMMAND} is not implemented yet" ;;
esac
