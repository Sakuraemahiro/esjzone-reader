"""Audit the packaged dex for anything that would fail at class-load time on a device.

Reads the dex string table (all type descriptors live there), then checks every referenced
class against the app classes, jsoup, and android.jar. Also flags Java 8 APIs that only exist
from API 24 unless they were desugared away.
"""
import os
import struct
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

DEX = r"F:\deepseek harness\EsjZoneReader\build\dex\classes.dex"
CLASSES = r"F:\deepseek harness\EsjZoneReader\build\classes"
JSOUP = r"F:\deepseek harness\EsjZoneReader\libs\jsoup-1.14.3.jar"
ANDROID = r"F:\deepseek harness\sdkwork\sdk\platforms\android-13\android.jar"


def read_strings(path):
    blob = open(path, "rb").read()
    assert blob[:4] == b"dex\n", "not a dex file"
    string_ids_size, string_ids_off = struct.unpack_from("<II", blob, 56)
    out = []
    for i in range(string_ids_size):
        offset = struct.unpack_from("<I", blob, string_ids_off + i * 4)[0]
        # string_data_item: uleb128 utf16 length, then MUTF-8 bytes, NUL terminated
        pos = offset
        shift = 0
        while True:
            byte = blob[pos]
            pos += 1
            if not byte & 0x80:
                break
            shift += 7
        end = blob.index(b"\x00", pos)
        out.append(blob[pos:end].decode("utf-8", "replace"))
    return out


def jar_classes(path):
    names = set()
    with zipfile.ZipFile(path) as archive:
        for entry in archive.namelist():
            if entry.endswith(".class"):
                names.add(entry[:-6])
    return names


print("dex:", DEX, os.path.getsize(DEX), "bytes")
strings = read_strings(DEX)
print("dex strings:", len(strings))

types = sorted({s for s in strings if len(s) > 2 and s.startswith("L") and s.endswith(";")})
print("referenced types:", len(types))

app = set()
for root, _dirs, files in os.walk(CLASSES):
    for name in files:
        if name.endswith(".class"):
            full = os.path.join(root, name)
            app.add(os.path.relpath(full, CLASSES)[:-6].replace(os.sep, "/"))

jsoup = jar_classes(JSOUP)
android = jar_classes(ANDROID)
print("app classes:", len(app), " jsoup:", len(jsoup), " android.jar:", len(android))

missing = []
desugar_risky = {}
for desc in types:
    name = desc[1:-1]
    # array types and primitives
    if name.startswith("["):
        continue
    if name.startswith("java/") or name.startswith("javax/") or name.startswith("sun/"):
        if name.startswith(("java/util/stream/", "java/util/function/", "java/util/Optional",
                            "java/time/", "java/util/concurrent/Flow")):
            desugar_risky[name] = desugar_risky.get(name, 0) + 1
        continue
    if name in app or name in jsoup or name in android:
        continue
    missing.append(name)

print()
print("=== Java 8 APIs that need desugaring (must be empty for minSdk 21) ===")
if desugar_risky:
    for name in sorted(desugar_risky):
        print("   RISK", name)
else:
    print("   none - clean")

print()
print("=== referenced classes not found in app / jsoup / android.jar ===")
if missing:
    for name in sorted(missing)[:40]:
        print("   MISSING", name)
    print("   total:", len(missing))
else:
    print("   none")

print()
print("=== app classes actually in the dex ===")
app_in_dex = sorted(n for n in app if n.startswith("com/esjzone/reader"))
for name in app_in_dex:
    print("   ", name)
