"""Check that Fetcher.looksSignedOut() separates the soft-block page from real pages."""
import sys

sys.stdout.reconfigure(encoding="utf-8")

MARKERS = ['id="chapterList"', "forum-content", "product-item", "會員專區", "会员专区"]

PAGES = [
    ("soft block (forum index)", r"F:\deepseek harness\esj-source\home.html", True),
    ("favorites", r"C:\Users\abcd3\Desktop\我的收藏 - ESJ Zone.html", False),
    ("detail", r"C:\Users\abcd3\Desktop\【本篇完结】我被黑幕家族收养了 - 253 我的夫君们（完）"
               r"__https___www.esjzone.cc_forum_1744349889_537785.html - 輕小說 - ESJ Zone.html", False),
    ("chapter", r"C:\Users\abcd3\Desktop\【本篇完结】我被黑幕家族收养了 - "
                r"000 加入勇者队伍的第一天 - ESJ Zone.html", False),
]

failures = 0
for label, path, expect_signed_out in PAGES:
    text = open(path, "rb").read().decode("utf-8", "replace")
    signed_out = not any(marker in text for marker in MARKERS)
    verdict = "OK " if signed_out == expect_signed_out else "WRONG"
    if signed_out != expect_signed_out:
        failures += 1
    print("%-5s %-26s bytes=%-8d looksSignedOut=%-5s expected=%s"
          % (verdict, label, len(text), signed_out, expect_signed_out))

print()
print("failures:", failures)
sys.exit(1 if failures else 0)
