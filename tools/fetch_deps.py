"""Fetch the two offline dependencies for the ESJZone reader app.

- jsoup 1.14.3: the newest release with no java.util.stream / Optional references,
  so it still runs on Android 5.0-6.0 (API 21-23).
- OpenCC TSCharacters.txt: traditional -> simplified character table, packed into
  a compact asset the app loads at startup.
"""
import os
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

ROOT = r"F:\deepseek harness\EsjZoneReader"
LIBS = os.path.join(ROOT, "libs")
ASSETS = os.path.join(ROOT, "assets")
UA = {"User-Agent": "Mozilla/5.0"}

os.makedirs(LIBS, exist_ok=True)
os.makedirs(ASSETS, exist_ok=True)


def fetch(url):
    for attempt in range(4):
        try:
            return urllib.request.urlopen(
                urllib.request.Request(url, headers=UA), timeout=180).read()
        except Exception as error:
            print("   retry %d: %r" % (attempt + 1, str(error)[:70]))
    raise SystemExit("failed: " + url)


# ---------------------------------------------------------------- jsoup
name = "jsoup-1.14.3.jar"
dest = os.path.join(LIBS, name)
if not os.path.exists(dest):
    body = fetch("https://repo1.maven.org/maven2/org/jsoup/jsoup/1.14.3/jsoup-1.14.3.jar")
    with open(dest, "wb") as handle:
        handle.write(body)
print("jsoup: %s (%d bytes)" % (dest, os.path.getsize(dest)))

# ---------------------------------------------------------------- opencc t2s
raw = fetch("https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt")
text = raw.decode("utf-8")

pairs = []
skipped = 0
for line in text.splitlines():
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    parts = line.split()
    if len(parts) < 2:
        skipped += 1
        continue
    source, targets = parts[0], parts[1:]
    if len(source) != 1:
        skipped += 1
        continue
    # several candidate readings are listed; the first is OpenCC's default
    pairs.append((source, targets[0]))

print("opencc lines: %d mapped, %d skipped (multi-char entries)" % (len(pairs), skipped))
print("samples:", pairs[:5], pairs[-5:])

# one "trad\tsimp" pair per line; the app builds a char->char map from it
out = os.path.join(ASSETS, "t2s.tsv")
with open(out, "w", encoding="utf-8", newline="\n") as handle:
    for source, target in pairs:
        handle.write("%s\t%s\n" % (source, target))
print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))

# characters that map to themselves are the majority; report the real work
changed = [p for p in pairs if p[0] != p[1]]
print("characters that actually change: %d" % len(changed))
