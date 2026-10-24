"""Validate an EPUB produced by the app's EpubBuilder."""
import os
import posixpath
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

# split output inherits its source, which may legitimately have no cover or no images
TOLERANT = "--split" in sys.argv


NS = {
    "c": "urn:oasis:names:tc:opendocument:xmlns:container",
    "opf": "http://www.idpf.org/2007/opf",
    "x": "http://www.w3.org/1999/xhtml",
    "ncx": "http://www.daisy.org/z3986/2005/ncx/",
}

problems = []


def fail(message):
    problems.append(message)


def check(path):
    with zipfile.ZipFile(path) as z:
        if z.testzip():
            fail("corrupt zip entry")
        names = z.namelist()

        # --- mimetype must be the first entry and stored uncompressed
        if names[0] != "mimetype":
            fail("first entry is %s, not mimetype" % names[0])
        if z.getinfo("mimetype").compress_type != zipfile.ZIP_STORED:
            fail("mimetype is compressed")
        if z.read("mimetype") != b"application/epub+zip":
            fail("mimetype payload wrong")

        # --- container
        container = ET.fromstring(z.read("META-INF/container.xml"))
        rootfile = container.find(".//c:rootfile", NS)
        if rootfile is None:
            fail("container.xml has no rootfile")
            return
        opf_path = rootfile.get("full-path")
        if opf_path not in names:
            fail("container points at missing %s" % opf_path)
            return

        # --- package
        opf = ET.fromstring(z.read(opf_path))
        base = os.path.dirname(opf_path)
        items = {}
        for item in opf.findall(".//opf:manifest/opf:item", NS):
            full = os.path.normpath(os.path.join(base, item.get("href"))).replace("\\", "/")
            items[item.get("id")] = (item.get("href"), full, item.get("media-type"),
                                     item.get("properties") or "")
            if full not in names:
                fail("manifest item %s -> missing %s" % (item.get("id"), full))

        spine = [ref.get("idref") for ref in opf.findall(".//opf:spine/opf:itemref", NS)]
        for idref in spine:
            if idref not in items:
                fail("spine references unknown id %s" % idref)

        chapters = [i for i in spine if re.match(r"^c\d+$", i or "")]
        if not chapters:
            fail("no chapters in the spine")

        images = [i for i, v in items.items() if (v[2] or "").startswith("image/")]
        cover = [i for i, v in items.items() if "cover-image" in v[3]]
        if not TOLERANT:
            if not images:
                fail("no image items in the manifest")
            if not cover:
                fail("no cover-image item (properties=cover-image missing)")

        # --- navigation must mirror the chapters and keep the volume nesting
        nav_name = next((v[1] for v in items.values() if v[1].endswith("nav.xhtml")), None)
        ncx_name = next((v[1] for v in items.values() if v[1].endswith("toc.ncx")), None)
        if nav_name is None:
            fail("nav.xhtml not in the manifest")
        else:
            nav = ET.fromstring(z.read(nav_name))
            links = [a.get("href") for a in nav.findall(".//x:nav//x:a", NS)]
            if len(links) != len(chapters):
                fail("nav lists %d links but the spine has %d chapters"
                     % (len(links), len(chapters)))
            if len(set(links)) != len(links):
                fail("nav has duplicate hrefs")
            spans = nav.findall(".//x:nav//x:span", NS)
            print("  nav: %d links, %d volume labels" % (len(links), len(spans)))
        if ncx_name is None:
            fail("toc.ncx not in the manifest")
        else:
            ncx = ET.fromstring(z.read(ncx_name))
            points = ncx.findall(".//ncx:navPoint", NS)
            if len(points) != len(links) + len(spans):
                fail("ncx has %d points, expected %d (chapters + volumes)"
                     % (len(points), len(links) + len(spans)))
            orders = [int(p.get("playOrder")) for p in points]
            if sorted(orders) != list(range(1, len(orders) + 1)):
                fail("ncx playOrder is not a clean 1..n sequence")
            print("  ncx: %d points" % len(points))

        # --- navigation targets must actually resolve inside the archive
        if nav_name is not None:
            nav_dir = posixpath.dirname(nav_name)
            for href in links:
                target = posixpath.normpath(posixpath.join(nav_dir, href.split("#")[0]))
                if target not in names:
                    fail("nav target does not resolve: %s -> %s" % (href, target))
        if ncx_name is not None:
            ncx_dir = posixpath.dirname(ncx_name)
            for point in points:
                content = point.find("ncx:content", NS)
                src = content.get("src") if content is not None else None
                if not src:
                    fail("ncx navPoint has no content src")
                    continue
                target = posixpath.normpath(posixpath.join(ncx_dir, src.split("#")[0]))
                if target not in names:
                    fail("ncx target does not resolve: %s -> %s (ncx lives in %s)"
                         % (src, target, ncx_dir or "the root"))

        # --- every document well formed, every image reference resolvable
        referenced = set()
        empty = 0
        for idref in chapters:
            full = items[idref][1]
            body = z.read(full)
            try:
                text = "".join(ET.fromstring(body).itertext())
            except ET.ParseError as error:
                fail("%s is not well-formed XML (%s)" % (full, error))
                continue
            if not text.strip():
                empty += 1
            for src in re.findall(r'<img[^>]+src="([^"]+)"', body.decode("utf-8")):
                if "://" in src:
                    fail("%s references a remote image %s" % (full, src))
                referenced.add(os.path.normpath(os.path.join(base, src)).replace("\\", "/"))
        for ref in referenced:
            if ref not in names:
                fail("image referenced but not packaged: %s" % ref)
        if empty:
            fail("%d chapter documents are empty" % empty)

        # --- no placeholder should appear when every chapter was supplied
        placeholders = 0
        for idref in chapters:
            text = "".join(ET.fromstring(z.read(items[idref][1])).itertext())
            if "未能下载" in text or "无法获取" in text:
                placeholders += 1

        print("  entries=%d chapters=%d images=%d imagesReferenced=%d placeholders=%d"
              % (len(names), len(chapters), len(images), len(referenced), placeholders))
        return len(chapters)


if __name__ == "__main__":
    for target in [a for a in sys.argv[1:] if not a.startswith('--')]:
        print("validating", os.path.basename(target))
        check(target)
    print()
    if problems:
        print("PROBLEMS (%d):" % len(problems))
        for line in problems:
            print("  -", line)
        sys.exit(1)
    print("ALL CHECKS PASSED")
