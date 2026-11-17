#!/usr/bin/env python3
"""Generate an update-manifest.json for one source using the final signed APK.

For two sources, run twice with the same APK and each source's own base URL.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
from urllib.parse import quote, urlsplit


MAX_APK_BYTES = 200 * 1024 * 1024


def find_aapt() -> str | None:
    if executable := shutil.which("aapt2"):
        return executable
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and os.environ.get("LOCALAPPDATA"):
        sdk = pathlib.Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"
    if not sdk:
        return None
    binary = "aapt2.exe" if os.name == "nt" else "aapt2"
    tools = pathlib.Path(sdk) / "build-tools"
    candidates = [
        path for path in tools.glob(f"*/{binary}")
        if re.fullmatch(r"\d+(?:\.\d+)+", path.parent.name)
    ]
    newest = max(candidates, default=None, key=lambda path: tuple(map(int, path.parent.name.split("."))))
    return str(newest) if newest else None


def read_apk_identity(aapt: str, apk: pathlib.Path) -> tuple[int, str]:
    try:
        result = subprocess.run(
            [aapt, "dump", "badging", str(apk)],
            capture_output=True, check=True, encoding="utf-8", errors="replace",
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"cannot read APK metadata: {error}")
    package = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",
        result.stdout, re.MULTILINE,
    )
    if not package or package[1] != "com.wwkhub.bluefin" or not package[3]:
        raise SystemExit("APK must be com.wwkhub.bluefin with a version name")
    return int(package[2]), package[3]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--version-code", type=int, help="optional assertion against the APK")
    parser.add_argument("--version-name", help="optional assertion against the APK")
    parser.add_argument("--aapt", help="path to Android SDK aapt2")
    parser.add_argument("--base-url", required=True, help="HTTPS directory holding this source’s APK")
    parser.add_argument("--notes", default="")
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("update-manifest.json"))
    args = parser.parse_args()

    if not args.apk.is_file() or not 0 < args.apk.stat().st_size <= MAX_APK_BYTES:
        parser.error("APK must be a file no larger than 200 MiB")
    aapt = args.aapt or find_aapt()
    if not aapt:
        parser.error("aapt2 not found: set ANDROID_HOME or pass --aapt")
    code, name = read_apk_identity(aapt, args.apk)
    if not 0 < code <= 2_100_000_000:
        parser.error("invalid APK versionCode")
    if args.version_code is not None and args.version_code != code:
        parser.error("--version-code does not match the APK")
    if args.version_name is not None and args.version_name != name:
        parser.error("--version-name does not match the APK")
    base = args.base_url.rstrip("/")
    parsed = urlsplit(base)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error("distribution base URL must be an HTTPS directory without credentials, query or fragment")

    with args.apk.open("rb") as file:
        digest = hashlib.sha256()
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    size = args.apk.stat().st_size
    manifest = {
        "versionCode": code,
        "versionName": name,
        "apkUrl": f"{base}/{quote(args.apk.name)}",
        "sha256": digest.hexdigest(),
        "sizeBytes": size,
    }
    if args.notes:
        manifest["notes"] = args.notes
    args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.out} ({size} bytes, sha256 {manifest['sha256']})")


if __name__ == "__main__":
    main()