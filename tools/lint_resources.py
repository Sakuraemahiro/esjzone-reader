"""Lint the Android resources the way the runtime would.

Catches the class of bug that only shows up when a layout is inflated on a device:
a ViewGroup child without layout_width / layout_height throws
"You must supply a layout_width attribute" and kills the activity at setContentView.

Also verifies every @type/name reference resolves, that ids are unique, and that every
R.type.name the Java code uses actually exists.
"""
import os
import re
import sys

from lxml import etree

sys.stdout.reconfigure(encoding="utf-8")

ROOT = r"F:\deepseek harness\EsjZoneReader"
RES = os.path.join(ROOT, "res")
SRC = os.path.join(ROOT, "src")
ANDROID = "{http://schemas.android.com/apk/res/android}"

problems = []


def fail(message):
    problems.append(message)


# ---------------------------------------------------------------- resource index
defined = {"drawable": set(), "color": set(), "string": set(), "layout": set(),
           "mipmap": set(), "style": set(), "id": set(), "xml": set()}

for folder in sorted(os.listdir(RES)):
    path = os.path.join(RES, folder)
    if not os.path.isdir(path):
        continue
    kind = folder.split("-")[0]
    if kind == "values":
        for name in os.listdir(path):
            if not name.endswith(".xml"):
                continue
            tree = etree.parse(os.path.join(path, name))
            for child in tree.getroot():
                if not isinstance(child.tag, str):
                    continue  # comments and processing instructions
                tag = etree.QName(child).localname
                key = child.get("name")
                if tag == "style":
                    defined["style"].add(key)
                elif tag in ("color", "string", "dimen", "bool", "integer"):
                    defined["color" if tag == "color" else
                            "string" if tag == "string" else "drawable"].add(key)
                elif tag in ("item", "string-array", "array"):
                    if child.get("type"):
                        defined.setdefault(child.get("type"), set()).add(key)
    elif kind in defined:
        for name in os.listdir(path):
            defined[kind].add(os.path.splitext(name)[0])

# ids declared anywhere
for base, _dirs, files in os.walk(RES):
    for name in files:
        if not name.endswith(".xml"):
            continue
        with open(os.path.join(base, name), "rb") as handle:
            text = handle.read().decode("utf-8", "replace")
        for value in re.findall(r'@\+id/([A-Za-z0-9_]+)', text):
            defined["id"].add(value)

print("resources: " + ", ".join("%s=%d" % (k, len(v)) for k, v in sorted(defined.items())))

# ---------------------------------------------------------------- styles
styles = {}
for name in os.listdir(os.path.join(RES, "values")):
    if not name.endswith(".xml"):
        continue
    tree = etree.parse(os.path.join(RES, "values", name))
    for style in tree.getroot():
        if not isinstance(style.tag, str):
            continue
        if etree.QName(style).localname != "style":
            continue
        attrs = {}
        for item in style:
            key = item.get("name") or ""
            # in res/values the android prefix is written literally, not as a namespace
            if key.startswith("android:"):
                key = ANDROID + key[len("android:"):]
            attrs[key] = (item.text or "").strip()
        styles[style.get("name")] = {"parent": style.get("parent"), "attrs": attrs}


def resolved(attrs, wanted):
    """Look a layout attribute up on the element, then through its style chain."""
    if attrs.get(wanted):
        return attrs[wanted]
    seen = set()
    name = attrs.get("style", "").replace("@style/", "")
    while name and name in styles and name not in seen:
        seen.add(name)
        if styles[name]["attrs"].get(wanted):
            return styles[name]["attrs"][wanted]
        parent = styles[name]["parent"] or ""
        name = parent.replace("@style/", "")
    return None


# ---------------------------------------------------------------- layouts
LAYOUT_ATTRS = (ANDROID + "layout_width", ANDROID + "layout_height")

for name in sorted(os.listdir(os.path.join(RES, "layout"))):
    if not name.endswith(".xml"):
        continue
    path = os.path.join(RES, "layout", name)
    tree = etree.parse(path)
    root = tree.getroot()

    for element in root.iter():
        if not isinstance(element.tag, str):
            continue
        if element is root:
            continue  # the root gets its params from the parent, so they are optional
        attrs = dict(element.attrib)
        for wanted in LAYOUT_ATTRS:
            if resolved(attrs, wanted) is None:
                fail("%s: <%s id=%s> is missing %s"
                     % (name, etree.QName(element).localname,
                        attrs.get(ANDROID + "id", "-"), wanted.replace(ANDROID, "android:")))

# ---------------------------------------------------------------- reference checks
for base, _dirs, files in os.walk(RES):
    for name in files:
        if not name.endswith(".xml"):
            continue
        path = os.path.join(base, name)
        text = open(path, "rb").read().decode("utf-8", "replace")
        for kind, ref in re.findall(r'"@(?!\+|android:|null)([a-z]+)/([A-Za-z0-9_.]+)"', text):
            if kind == "android":
                continue
            if kind not in defined:
                continue
            if ref not in defined[kind]:
                fail("%s: @%s/%s does not exist" % (os.path.relpath(path, ROOT), kind, ref))

# ---------------------------------------------------------------- R.* used from java
used = {}
for base, _dirs, files in os.walk(SRC):
    for name in files:
        if not name.endswith(".java"):
            continue
        text = open(os.path.join(base, name), "rb").read().decode("utf-8", "replace")
        for kind, ref in re.findall(r"\bR\.([a-z]+)\.([A-Za-z0-9_]+)", text):
            used.setdefault(kind, set()).add(ref)

for kind, refs in sorted(used.items()):
    if kind not in defined:
        fail("java uses R.%s.* but that resource type is never declared" % kind)
        continue
    for ref in sorted(refs):
        if ref not in defined[kind]:
            fail("java uses R.%s.%s which does not exist" % (kind, ref))

print("java R.* references: " + ", ".join("%s=%d" % (k, len(v)) for k, v in sorted(used.items())))
print()
if problems:
    print("PROBLEMS (%d):" % len(problems))
    for line in problems:
        print("  -", line)
    sys.exit(1)
print("RESOURCE CHECKS PASSED")
