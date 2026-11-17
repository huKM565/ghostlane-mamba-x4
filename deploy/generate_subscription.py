#!/usr/bin/env python3
import argparse
import json
import os
import re
from pathlib import Path

LABELS = tuple(f"{provider}{index}" for provider in "mvt" for index in range(1, 5))
PROFILES = (
    ("Mamba 4x", "m"),
    ("VK 4x", "v"),
    ("Телемост 4x", "t"),
)


def field(text, name):
    match = re.search(rf"(?m)^  {re.escape(name)}: '?([^'\n]+)'?$", text)
    if not match:
        raise ValueError(f"managed config has no {name}")
    return match.group(1).strip()


def load_bundle(path):
    text = Path(path).read_text()
    parts = re.split(r"(?m)^###(XRAY|ROOM [mvt][1-4])\n", text)
    sections = {}
    for index in range(1, len(parts), 2):
        sections[parts[index]] = parts[index + 1]
    if "XRAY" not in sections:
        raise ValueError("managed bundle has no Xray config")
    xray = json.loads(sections["XRAY"])
    inbound = xray["inbounds"][0]
    vless = {
        "host": inbound["listen"],
        "port": inbound["port"],
        "id": inbound["settings"]["clients"][0]["id"],
    }
    rooms = {}
    for label in LABELS:
        config = sections.get(f"ROOM {label}")
        if config is None:
            raise ValueError(f"managed bundle has no room {label}")
        room = {
            "provider": field(config, "provider"),
            "room": field(config, "id"),
            "key": field(config, "key"),
            "transport": field(config, "transport"),
        }
        if any(char in room["room"] for char in "\r\n#"):
            raise ValueError(f"room {label} contains an unsafe character")
        rooms[label] = room
    return rooms, vless


def olc_link(room, name=None):
    link = f"olcrtc://{room['provider']}?{room['transport']}@{room['room']}#{room['key']}"
    return link if name is None else f"{link}${name}"


def vless_link(vless):
    host = vless["host"]
    if host in ("0.0.0.0", "::"):
        host = "127.0.0.1"
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    return f"vless://{vless['id']}@{host}:{vless['port']}?security=none&encryption=none#VLESS"


def render_group(group, rooms, vless):
    output = []
    bond_vless = vless_link(vless)
    for profile, prefix in PROFILES:
        selected = [rooms[f"{prefix}{index}"] for index in range(1, 5)]
        keys = {room["key"] for room in selected}
        output.append(olc_link(selected[0], f"{group} — {profile}"))
        output.append(f"##group: {group}")
        if len(keys) == 1:
            output.append("##rooms: " + ", ".join(room["room"] for room in selected[1:]))
        else:
            output.extend(f"##bond-room: {olc_link(room)}" for room in selected[1:])
        output.append(f"##bond-vless: {bond_vless}")
    ordered = [rooms[label] for label in LABELS]
    output.append(olc_link(ordered[0], f"{group} — Multi 12x"))
    output.append(f"##group: {group}")
    output.extend(f"##bond-room: {olc_link(room)}" for room in ordered[1:])
    output.append(f"##bond-vless: {bond_vless}")
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--vds", action="append", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not 1 <= len(args.vds) <= 2:
        raise ValueError("one or two VDS bundles are required")
    lines = []
    for item in args.vds:
        group, path = item.split("=", 1)
        if group not in ("Германия", "Финляндия"):
            raise ValueError("unexpected VDS group")
        rooms, vless = load_bundle(path)
        lines.extend(render_group(group, rooms, vless))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.tmp-{os.getpid()}")
    temporary.write_text("\n".join(lines) + "\n")
    temporary.chmod(0o600)
    temporary.replace(output)


if __name__ == "__main__":
    main()
