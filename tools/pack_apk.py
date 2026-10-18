"""Combine aapt2's resource APK with classes.dex into an unsigned APK.

resources.arsc must stay uncompressed (and later 4-byte aligned by zipalign),
because Android 11+ refuses to install an APK that stores it compressed.
"""
import sys
import zipfile

base_apk, dex_file, out_apk = sys.argv[1], sys.argv[2], sys.argv[3]
CHECKSUM_NAMES = {"META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA"}

with zipfile.ZipFile(base_apk, "r") as src, zipfile.ZipFile(out_apk, "w") as dst:
    for item in src.infolist():
        upper = item.filename.upper()
        if upper.startswith("META-INF/") and (
                upper.endswith(".MF") or upper.endswith(".SF") or upper.endswith(".RSA")
                or upper.endswith(".DSA")):
            continue
        info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        info.external_attr = item.external_attr
        info.internal_attr = item.internal_attr
        info.create_system = item.create_system
        data = src.read(item.filename)
        if item.filename == "resources.arsc":
            info.compress_type = zipfile.ZIP_STORED
        else:
            info.compress_type = zipfile.ZIP_DEFLATED
        dst.writestr(info, data)

    dex_info = zipfile.ZipInfo("classes.dex", date_time=(2010, 1, 1, 0, 0, 0))
    dex_info.compress_type = zipfile.ZIP_DEFLATED
    with open(dex_file, "rb") as handle:
        dst.writestr(dex_info, handle.read())

names = zipfile.ZipFile(out_apk).namelist()
print("packed %s: %d entries" % (out_apk, len(names)))
for required in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
    if required not in names:
        sys.exit("missing %s in the packaged APK" % required)
