#!/usr/bin/env python3

import argparse
import base64
import binascii
import os
from pathlib import Path
import sys

SECRET_NAMES = (
    "TV_SIGNING_JKS_BASE64",
    "TV_SIGNING_STORE_PASSWORD",
    "TV_SIGNING_KEY_ALIAS",
    "TV_SIGNING_KEY_PASSWORD",
)


def java_property_escape(value: str) -> str:
    out: list[str] = []
    for index, char in enumerate(value):
        code = ord(char)
        if char == " ":
            out.append("\\ " if index == 0 else " ")
        elif char == "\\":
            out.append("\\\\")
        elif char == "\t":
            out.append("\\t")
        elif char == "\n":
            out.append("\\n")
        elif char == "\r":
            out.append("\\r")
        elif char == "\f":
            out.append("\\f")
        elif char in "=:# !":
            out.append("\\" + char)
        elif 0x20 <= code <= 0x7E:
            out.append(char)
        elif code <= 0xFFFF:
            out.append(f"\\u{code:04x}")
        else:
            code -= 0x10000
            high = 0xD800 + (code >> 10)
            low = 0xDC00 + (code & 0x3FF)
            out.append(f"\\u{high:04x}\\u{low:04x}")
    return "".join(out)


def fail(message: str) -> int:
    print(f"::error::{message}", file=sys.stderr)
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Inject GitHub Actions JKS signing secrets into a temporary Magisk config."
    )
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--keystore", required=True, type=Path)
    args = parser.parse_args()

    values = {name: os.environ.get(name, "") for name in SECRET_NAMES}
    configured = [name for name, value in values.items() if value]

    if not configured:
        print("No custom signing secrets configured; using the default debug signing key.")
        return 0

    missing = [name for name, value in values.items() if not value]
    if missing:
        return fail(
            "Custom signing is only partially configured. Missing Actions secrets: "
            + ", ".join(missing)
        )

    encoded = "".join(values["TV_SIGNING_JKS_BASE64"].split())
    try:
        keystore_bytes = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError):
        return fail("TV_SIGNING_JKS_BASE64 is not valid Base64 data.")

    if not keystore_bytes:
        return fail("TV_SIGNING_JKS_BASE64 decoded to an empty file.")

    args.keystore.parent.mkdir(parents=True, exist_ok=True)
    args.keystore.write_bytes(keystore_bytes)
    os.chmod(args.keystore, 0o600)

    if not args.config.is_file():
        return fail(f"Build config does not exist: {args.config}")

    existing = args.config.read_text(encoding="utf-8")
    if existing and not existing.endswith("\n"):
        existing += "\n"

    signing_properties = {
        "keyStore": str(args.keystore.resolve()),
        "keyStorePass": values["TV_SIGNING_STORE_PASSWORD"],
        "keyAlias": values["TV_SIGNING_KEY_ALIAS"],
        "keyPass": values["TV_SIGNING_KEY_PASSWORD"],
    }

    with args.config.open("w", encoding="ascii", newline="\n") as stream:
        stream.write(existing)
        for key, value in signing_properties.items():
            stream.write(f"{key}={java_property_escape(value)}\n")

    print("Custom JKS signing configuration prepared.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
