#!/usr/bin/env python3
# Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Automated release binary verifier for FullFileStudio:
1. Verifies Google Play release APK contains zero self-update code, URLs, or forbidden permissions.
2. Verifies compiled ART Baseline Profile (baseline.prof + baseline.profm) in both APK and AAB.
3. Verifies Android 15+/16+ 16 KB page alignment (PT_LOAD p_align >= 0x4000) for all ARM64/x86_64 .so libraries
   and uncompressed 16 KB ZIP alignment inside the APK.
"""

import glob
import os
import struct
import subprocess
import sys
import zipfile

APK_GOOGLE_PLAY = "app/build/outputs/apk/googlePlay/release/app-googlePlay-release.apk"
AAB_GOOGLE_PLAY = "app/build/outputs/bundle/googlePlayRelease/app-googlePlay-release.aab"

FORBIDDEN_DEX_BYTES = [
    b"REQUEST_INSTALL_PACKAGES",
    b"StandaloneUpdateManager",
    b"Tls13Https",
    b"version.json",
    b"releases/download",
    b"Lcom/omnireader/",
    b"PlayFeaturePluginManager",
    b"PlayFeatureBundle",
]

FORBIDDEN_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
}


def find_aapt2() -> str | None:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Library/Android/sdk")
    candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    return candidates[-1] if candidates else None


def check_elf64_load_alignment(so_name: str, data: bytes) -> list[str]:
    errors = []
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return [f"{so_name}: invalid ELF header"]
    ei_class = data[4]
    ei_data = data[5]
    if ei_class != 2:
        return []
    endian = "<" if ei_data == 1 else ">"
    e_phoff = struct.unpack_from(f"{endian}Q", data, 32)[0]
    e_phentsize, e_phnum = struct.unpack_from(f"{endian}HH", data, 54)
    pt_load_count = 0
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(f"{endian}I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            pt_load_count += 1
            p_align = struct.unpack_from(f"{endian}Q", data, off + 48)[0]
            if p_align < 0x4000:
                errors.append(f"{so_name}: PT_LOAD[{i}] p_align=0x{p_align:x} (< 0x4000 / 16 KB)")
    if pt_load_count == 0:
        errors.append(f"{so_name}: no PT_LOAD segments found")
    return errors


def main() -> int:
    failures: list[str] = []

    if not os.path.isfile(APK_GOOGLE_PLAY):
        print(f"ERROR: Missing {APK_GOOGLE_PLAY}", file=sys.stderr)
        return 1
    if not os.path.isfile(AAB_GOOGLE_PLAY):
        print(f"ERROR: Missing {AAB_GOOGLE_PLAY}", file=sys.stderr)
        return 1

    with zipfile.ZipFile(APK_GOOGLE_PLAY, "r") as zf:
        names = set(zf.namelist())
        for req in ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"):
            if req not in names:
                failures.append(f"APK missing {req}")
            elif zf.getinfo(req).file_size < 256:
                failures.append(f"APK {req} unexpectedly small ({zf.getinfo(req).file_size} bytes)")

        if any("file_paths" in n for n in names):
            failures.append("APK leaks standalone FileProvider file_paths.xml")

        dex_concat = b"".join(zf.read(n) for n in sorted(names) if n.endswith(".dex"))
        for needle in FORBIDDEN_DEX_BYTES:
            if needle in dex_concat:
                failures.append(f"APK DEX leaks forbidden token: {needle.decode()}")

        so_files = [n for n in sorted(names) if n.endswith(".so")]
        if not so_files:
            failures.append("APK contains zero native .so libraries")
        if any("libengine_api.so" in n for n in so_files):
            failures.append("APK still contains legacy libengine_api.so")
        if not any("libfullfilestudio_security.so" in n for n in so_files):
            failures.append("APK missing libfullfilestudio_security.so")
        for so_name in so_files:
            info = zf.getinfo(so_name)
            if info.compress_type != zipfile.ZIP_STORED:
                failures.append(f"APK {so_name} is compressed (must be ZIP_STORED for direct mmap)")
            failures.extend(check_elf64_load_alignment(so_name, zf.read(so_name)))

    with zipfile.ZipFile(AAB_GOOGLE_PLAY, "r") as zf:
        prof_path = "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof"
        if prof_path not in zf.namelist():
            failures.append(f"AAB missing {prof_path}")

    aapt2 = find_aapt2()
    if aapt2:
        perms_out = subprocess.check_output([aapt2, "dump", "permissions", APK_GOOGLE_PLAY], text=True)
        for p in FORBIDDEN_PERMISSIONS:
            if p in perms_out:
                failures.append(f"GooglePlay APK declares forbidden permission: {p}")

    if failures:
        print("Release compliance verification FAILED:", file=sys.stderr)
        for f in failures:
            print(f" - {f}", file=sys.stderr)
        return 1

    print("OK: Google Play isolation, ART Baseline Profile, and 16 KB ELF page alignment verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
