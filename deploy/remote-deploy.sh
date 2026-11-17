#!/usr/bin/env bash
set -euo pipefail

stage="$1"
olcrtc_hash="$2"
xray_hash="$3"
backup=/var/lib/ilyukhaline/rollback
trap 'rm -rf "${stage}"' EXIT
ready=false
declare -a previous_active=()
declare -a new_started=()
declare -a units=(ilyukhaline-xray.service)
for label in m1 m2 m3 m4 v1 v2 v3 v4 t1 t2 t3 t4; do
  units+=("ilyukhaline-olcrtc@${label}.service")
done

rollback() {
  trap - ERR
  set +e
  if [ "${ready}" != true ]; then
    exit 1
  fi
  for unit in "${new_started[@]}"; do
    systemctl stop "${unit}"
  done
  rm -rf /etc/ilyukhaline /opt/ilyukhaline
  rm -f /etc/systemd/system/ilyukhaline-olcrtc@.service /etc/systemd/system/ilyukhaline-xray.service
  if [ -d "${backup}/etc" ]; then
    cp -a "${backup}/etc" /etc/ilyukhaline
  fi
  if [ -d "${backup}/opt" ]; then
    cp -a "${backup}/opt" /opt/ilyukhaline
  fi
  if [ -d "${backup}/units" ]; then
    cp -a "${backup}/units/." /etc/systemd/system/
  fi
  systemctl daemon-reload
  for unit in "${previous_active[@]}"; do
    systemctl restart "${unit}"
  done
  exit 1
}
trap rollback ERR

printf '%s  %s\n' "${olcrtc_hash}" "${stage}/olcrtc" | sha256sum -c -
printf '%s  %s\n' "${xray_hash}" "${stage}/xray" | sha256sum -c -

rm -rf "${backup}"
mkdir -p "${backup}/units"
chmod 700 "${backup}"
if [ -d /etc/ilyukhaline ]; then
  cp -a /etc/ilyukhaline "${backup}/etc"
fi
if [ -d /opt/ilyukhaline ]; then
  cp -a /opt/ilyukhaline "${backup}/opt"
fi
for unit in ilyukhaline-olcrtc@.service ilyukhaline-xray.service; do
  if [ -f "/etc/systemd/system/${unit}" ]; then
    cp -a "/etc/systemd/system/${unit}" "${backup}/units/${unit}"
  fi
done
for unit in "${units[@]}"; do
  if systemctl is-active --quiet "${unit}"; then
    previous_active+=("${unit}")
  else
    new_started+=("${unit}")
  fi
done
ready=true

for label in m1 v1; do
  if [ -f "/etc/ilyukhaline/${label}.yaml" ]; then
    cp -a "/etc/ilyukhaline/${label}.yaml" "${stage}/${label}.yaml"
  elif [ -f "/etc/olcrtc/${label}.yaml" ]; then
    cp -a "/etc/olcrtc/${label}.yaml" "${stage}/${label}.yaml"
  fi
done
if [ -f /etc/olcrtc/m1.yaml ] || [ -f /etc/olcrtc/v1.yaml ]; then
  systemctl disable olcrtc@m1.service olcrtc@v1.service 2>/dev/null || true
  systemctl stop olcrtc@m1.service olcrtc@v1.service 2>/dev/null || true
fi

mkdir -p /opt/ilyukhaline/bin /etc/ilyukhaline /etc/systemd/system
install -m 755 "${stage}/olcrtc" /opt/ilyukhaline/bin/.olcrtc.new
mv -f /opt/ilyukhaline/bin/.olcrtc.new /opt/ilyukhaline/bin/olcrtc
install -m 755 "${stage}/xray" /opt/ilyukhaline/bin/.xray.new
mv -f /opt/ilyukhaline/bin/.xray.new /opt/ilyukhaline/bin/xray
for file in "${stage}"/*.yaml; do
  name="${file##*/}"
  install -m 600 "${file}" "/etc/ilyukhaline/.${name}.new"
  mv -f "/etc/ilyukhaline/.${name}.new" "/etc/ilyukhaline/${name}"
done
install -m 600 "${stage}/xray.json" /etc/ilyukhaline/.xray.json.new
mv -f /etc/ilyukhaline/.xray.json.new /etc/ilyukhaline/xray.json
install -m 644 "${stage}/ilyukhaline-olcrtc@.service" /etc/systemd/system/.ilyukhaline-olcrtc@.service.new
mv -f /etc/systemd/system/.ilyukhaline-olcrtc@.service.new /etc/systemd/system/ilyukhaline-olcrtc@.service
install -m 644 "${stage}/ilyukhaline-xray.service" /etc/systemd/system/.ilyukhaline-xray.service.new
mv -f /etc/systemd/system/.ilyukhaline-xray.service.new /etc/systemd/system/ilyukhaline-xray.service

/opt/ilyukhaline/bin/olcrtc -version >/dev/null
/opt/ilyukhaline/bin/xray run -test -config /etc/ilyukhaline/xray.json >/dev/null
systemctl daemon-reload
for unit in "${units[@]}"; do
  systemctl enable "${unit}" >/dev/null
  systemctl restart "${unit}"
done
trap - ERR
printf 'deployed %s managed services\n' "${#units[@]}"
