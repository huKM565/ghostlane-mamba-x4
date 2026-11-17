#!/usr/bin/env bash
set -euo pipefail

CONFIG=""
COMMAND=""
declare -a SECRETS=()

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

  local name provider index url key previous other
  for name in SSH_TARGET ARTIFACT_DIR PUBLISH_PATH PUBLISH_URL VLESS_UUID VLESS_LISTEN; do
    require_value "${name}"
  done
  [[ "${SSH_TARGET}" =~ ^root@[A-Za-z0-9._:-]+$ ]] || die "SSH_TARGET must be root@host"
  [[ "${ARTIFACT_DIR}" = /* && "${ARTIFACT_DIR}" != *[[:space:]]* ]] || die "ARTIFACT_DIR must be an absolute path without whitespace"
  [[ "${PUBLISH_PATH}" = /* && "${PUBLISH_PATH}" != *[[:space:]]* ]] || die "PUBLISH_PATH must be an absolute path without whitespace"
  [[ "${PUBLISH_URL}" = https://* ]] || die "PUBLISH_URL must use HTTPS"
  [[ "${VLESS_UUID}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || die "VLESS_UUID is not a UUID"
  [[ "${VLESS_LISTEN}" =~ ^(127\.0\.0\.1|\[::1\]):[0-9]+$ ]] || die "VLESS_LISTEN must use a loopback address"
  local port
  port="${VLESS_LISTEN##*:}"
  ((port >= 1 && port <= 65535)) || die "VLESS_LISTEN port is out of range"

  SECRETS=("${VLESS_UUID}")
  for provider in MAMBA VKCALLS TELEMOST; do
    for index in 1 2 3 4; do
      name="${provider}_URL_${index}"
      require_value "${name}"
      url="${!name}"
      name="${provider}_KEY_${index}"
      require_value "${name}"
      key="${!name}"
      [[ "${url}" = https://* ]] || die "${provider}_URL_${index} must use HTTPS"
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
  *) die "${COMMAND} is not implemented yet" ;;
esac
