"""Inspect the text actually present in each chapter document of an EPUB."""
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

NS = {
    "c": "urn:oasis:names:tc:opendocument:xmlns:container",
    "opf": "http://www.idpf.org/2007/opf",
    "x": "http://www.w3.org/1999/xhtml",
}


def inspect(path):
    print("=" * 78)
    print(path)
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        container = ET.fromstring(z.read("META-INF/container.xml"))
        opf_path = container.find(".//c:rootfile", NS).get("full-path")
        base = opf_path.rsplit("/", 1)[0] if "/" in opf_path else ""
        opf = ET.fromstring(z.read(opf_path))

        items = {}
        for item in opf.findall(".//opf:manifest/opf:item", NS):
            href = item.get("href")
            full = (base + "/" + href) if base else href
            items[item.get("id")] = full

        spine = [r.get("idref") for r in opf.findall(".//opf:spine/opf:itemref", NS)]
        hrefs = [items.get(i) for i in spine]
        print("  spine entries : %d   unique hrefs: %d" % (len(hrefs), len(set(hrefs))))

        dupes = {}
        for h in hrefs:
            dupes[h] = dupes.get(h, 0) + 1
        repeated = {h: n for h, n in dupes.items() if n > 1}
        if repeated:
            print("  !! duplicated spine hrefs:")
            for h, n in list(repeated.items())[:10]:
                print("     %-46s x%d" % (h, n))

        print("  --- per spine entry ---")
        for index, href in enumerate(hrefs[:12]):
            if href not in names:
                print("     [%2d] %-44s MISSING" % (index, href))
                continue
            text = "".join(ET.fromstring(z.read(href)).itertext())
            text = re.sub(r"\s+", " ", text).strip()
            print("     [%2d] %-44s %6d chars  %s"
                  % (index, href.rsplit("/", 1)[-1], len(text), text[:42]))
        if len(hrefs) > 12:
            print("     ... %d more" % (len(hrefs) - 12))
            # also check the last few
            for index in range(len(hrefs) - 3, len(hrefs)):
                href = hrefs[index]
                if href not in names:
                    print("     [%2d] %-44s MISSING" % (index, href))
                    continue
                text = "".join(ET.fromstring(z.read(href)).itertext())
                text = re.sub(r"\s+", " ", text).strip()
                print("     [%2d] %-44s %6d chars  %s"
                      % (index, href.rsplit("/", 1)[-1], len(text), text[:42]))


for target in sys.argv[1:]:
    inspect(target)
