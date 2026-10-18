"""Download a few real EPUBs from the mirror as test material for the offline tools."""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

CATALOG = r"F:\deepseek harness\EsjZoneReader\..\EsjZoneMirror\assets\catalog.json"
OUT = r"F:\deepseek harness\EsjZoneReader\testdata\epub-samples"
BASE = "https://raw.githubusercontent.com/FelixTennouji/ESJZone-epub-novel-mirror/main/"
UA = {"User-Agent": "Mozilla/5.0"}

os.makedirs(OUT, exist_ok=True)
catalog = json.load(open(CATALOG, encoding="utf-8"))
rows = sorted(catalog["epub"], key=lambda r: r[3])

# a small, a medium and a large book, skipping the ones stored as split 7z volumes
picks = [rows[len(rows) // 20], rows[len(rows) // 3], rows[int(len(rows) * 0.8)]]

for index, row in enumerate(picks, 1):
    title, author, path, size = row
    url = BASE + urllib.parse.quote(path)
    dest = os.path.join(OUT, "sample%d.epub" % index)
    if os.path.exists(dest) and os.path.getsize(dest) == size:
        print("cached sample%d.epub (%d bytes)  %s" % (index, size, title[:40]))
        continue
    print("fetching %-46s %9d bytes" % (title[:44], size), flush=True)
    for attempt in range(4):
        try:
            body = urllib.request.urlopen(
                urllib.request.Request(url, headers=UA), timeout=300).read()
            break
        except Exception as error:
            print("   retry %d: %r" % (attempt + 1, str(error)[:60]))
            time.sleep(3)
    else:
        print("   giving up on", title)
        continue
    with open(dest, "wb") as handle:
        handle.write(body)
    print("   -> %s (%d bytes)" % (dest, len(body)))
