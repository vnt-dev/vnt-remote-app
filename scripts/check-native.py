#!/usr/bin/env python3
"""Verify both packaged ABIs, JNI exports, native media linkage and 16 KB alignment."""
import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")))
args = parser.parse_args()
ndk = args.sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt"
toolchain = next(ndk.glob("*/bin/llvm-readelf"))
nm = toolchain.with_name("llvm-nm")
expected = {"nativeCreate", "nativeCommand", "nativeInput", "nativeReadAudio", "nativeSurface", "nativeDestroy"}
with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory(prefix="vnt-native-check-") as temp:
    for abi in ("arm64-v8a", "x86_64"):
        name = f"lib/{abi}/libvnt_remote_android.so"
        info = apk.getinfo(name)
        assert info.compress_type == zipfile.ZIP_STORED, f"{abi}: native library is compressed"
        with args.apk.open("rb") as raw:
            raw.seek(info.header_offset)
            header = raw.read(30)
        filename_length, extra_length = struct.unpack_from("<HH", header, 26)
        offset = info.header_offset + 30 + filename_length + extra_length
        assert offset % 16384 == 0, f"{abi}: APK offset {offset} is not 16 KB aligned"
        library = Path(temp) / abi / "libvnt_remote_android.so"
        library.parent.mkdir()
        library.write_bytes(apk.read(name))
        program = subprocess.check_output([toolchain, "-l", library], text=True)
        loads = [line for line in program.splitlines() if line.strip().startswith("LOAD")]
        assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads), f"{abi}: ELF alignment"
        dynamic = subprocess.check_output([toolchain, "-d", library], text=True)
        assert "libmediandk.so" in dynamic and "libandroid.so" in dynamic, f"{abi}: missing platform libraries"
        symbols = subprocess.check_output([nm, "--dynamic", "--defined-only", library], text=True)
        for method in expected:
            assert f"Java_com_rustvnt_remote_NativeBridge_{method}" in symbols, f"{abi}: missing JNI method {method}"
        print(f"{abi}: JNI exports, MediaCodec linkage, ELF and APK 16 KB alignment OK")
