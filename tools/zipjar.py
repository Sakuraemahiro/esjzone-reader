"""Zip a directory of .class files into a jar (the bundled JDK has no jar.exe)."""
import os
import sys
import zipfile

src, out = sys.argv[1], sys.argv[2]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for root, _dirs, files in os.walk(src):
        for name in files:
            path = os.path.join(root, name)
            zf.write(path, os.path.relpath(path, src).replace(os.sep, "/"))
            count += 1
print("jar %s: %d entries, %d bytes" % (out, count, os.path.getsize(out)))

# An empty jar would make d8 fail with a confusing message.
if count == 0:
    sys.exit("no .class files found under " + src)
