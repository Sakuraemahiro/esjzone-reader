"""Repair MainActivity.java after a bad in-place patch, then apply the nav/picker edits."""
import io
import sys

sys.stdout.reconfigure(encoding="utf-8")

SRC = r"F:\deepseek harness\EsjZoneReader\src\com\esjzone\reader\MainActivity.java"
LAYOUT = r"F:\deepseek harness\EsjZoneReader\res\layout\activity_main.xml"

text = io.open(SRC, encoding="utf-8", newline="").read()

marker = "package com.esjzone.reader;"
index = text.find(marker)
if index > 0:
    print("repairing: dropping %d bytes of garbage before the package statement" % index)
    text = text[index:]
elif index < 0:
    raise SystemExit("package statement missing, cannot repair")

problems = []


def patch(old, new, label, expected=1, optional=False):
    global text
    count = text.count(old)
    if count == 0 and optional:
        print("  skip   :", label, "(already applied)")
        return
    if count != expected:
        problems.append("%s: expected %d match(es), found %d" % (label, expected, count))
        return
    text = text.replace(old, new)
    print("  patched:", label)


patch("import android.view.View;\n",
      "import android.view.View;\nimport android.view.WindowManager;\n",
      "import WindowManager", optional=True)

patch("        updateUserLabel();\n        if (cookies.hasSession()) {",
      "        Nav.wire(this);\n        updateUserLabel();\n        if (cookies.hasSession()) {",
      "Nav.wire")

patch("        downloader.enqueue(book);\n        adapter.notifyDataSetChanged();\n    }",
      "        openPicker(book);\n    }\n\n"
      "    /** Tap opens the chapter picker; the picker offers 全部下载 to skip the selection. */\n"
      "    private void openPicker(Book book) {\n"
      "        Intent intent = new Intent(this, ChapterPickerActivity.class);\n"
      "        intent.putExtra(ChapterPickerActivity.EXTRA_ID, book.id);\n"
      "        intent.putExtra(ChapterPickerActivity.EXTRA_TITLE, book.title);\n"
      "        intent.putExtra(ChapterPickerActivity.EXTRA_DETAIL, book.detailUrl);\n"
      "        intent.putExtra(ChapterPickerActivity.EXTRA_LATEST, book.latestTitle);\n"
      "        intent.putExtra(ChapterPickerActivity.EXTRA_UPDATED, book.updated);\n"
      "        startActivity(intent);\n"
      "    }",
      "openPicker")

patch("            if (pendingBook != null) {\n"
      "                downloader.enqueue(pendingBook);\n"
      "                pendingBook = null;\n"
      "            }",
      "            if (pendingBook != null) {\n"
      "                openPicker(pendingBook);\n"
      "                pendingBook = null;\n"
      "            }",
      "permission -> picker")

patch("    @Override\n    protected void onDestroy() {",
      "    @Override\n"
      "    protected void onPause() {\n"
      "        super.onPause();\n"
      "        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);\n"
      "    }\n\n"
      "    @Override\n"
      "    protected void onDestroy() {",
      "onPause releases keep-screen-on")

patch("        downloader.setListener(this);\n    }",
      "        downloader.setListener(this);\n"
      "        if (!downloader.activeTasks().isEmpty()) {\n"
      "            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);\n"
      "        }\n"
      "    }",
      "onResume keeps the screen on")

if problems:
    print()
    print("NOT WRITTEN:")
    for line in problems:
        print("  -", line)
    sys.exit(1)

io.open(SRC, "w", encoding="utf-8", newline="").write(text)
print("MainActivity.java written (%d bytes)" % len(text))

layout = io.open(LAYOUT, encoding="utf-8", newline="").read()
if "@layout/nav_bar" in layout:
    print("activity_main.xml already has the nav bar")
else:
    tail = layout.rstrip()
    cut = tail.rfind("</LinearLayout>")
    if cut < 0:
        raise SystemExit("activity_main.xml: unexpected ending")
    layout = tail[:cut] + '    <include layout="@layout/nav_bar" />\n</LinearLayout>\n'
    io.open(LAYOUT, "w", encoding="utf-8", newline="").write(layout)
    print("activity_main.xml: nav bar added")
