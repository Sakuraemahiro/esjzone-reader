package com.esjzone.reader;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a /detail/&lt;id&gt;.html page into metadata plus the table of contents.
 *
 * <p>The table of contents is not a uniform list. The site expresses a volume with any of
 * three things:</p>
 * <ul>
 *   <li>{@code <details><summary>卷名</summary>…}</li>
 *   <li>a bare {@code <summary>卷名</summary>} that is not inside any {@code <details>} — the
 *       "正文" volume of every book looks like this, and the mirror scripts miss it</li>
 *   <li>a non-empty {@code <p>}</li>
 * </ul>
 * <p>and a chapter with {@code <a href>}, which carries a clean {@code data-title} attribute.
 * Everything else (empty {@code <p class="non">}, {@code <br>}, {@code <span>}) is spacing.</p>
 */
public final class DetailParser {

    public static BookDetail parse(Document doc, String baseUri) {
        BookDetail detail = new BookDetail();

        Element heading = doc.selectFirst("h2.text-normal");
        if (heading == null) {
            heading = doc.selectFirst("h2");
        }
        if (heading != null) {
            detail.title = text(heading);
        }

        Elements items = doc.select("ul.book-detail > li");
        for (int i = 0; i < items.size(); i++) {
            String line = text(items.get(i));
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("作者")) {
                detail.author = afterLabel(line);
            } else if (line.startsWith("類型") || line.startsWith("类型")) {
                detail.genre = afterLabel(line);
            } else if (line.startsWith("更新日期")) {
                detail.updated = afterLabel(line);
            } else {
                detail.extra.add(line);
            }
        }

        Element cover = doc.selectFirst(".product-gallery img");
        if (cover != null) {
            detail.coverUrl = Site.canonical(baseUri, cover.absUrl("src"));
        }

        Element description = doc.selectFirst(".description");
        if (description != null) {
            detail.description = text(description);
        }

        Element list = doc.selectFirst("#chapterList");
        if (list != null) {
            detail.outline.addAll(outline(list.children(), 0, baseUri));
        }
        detail.id = Site.bookId(doc.location().length() > 0 ? doc.location() : baseUri);
        return detail;
    }

    /** True when the page really is a book detail page (i.e. the session is still signed in). */
    public static boolean looksLikeDetail(Document doc) {
        return doc.selectFirst("#chapterList") != null && doc.selectFirst("h2") != null;
    }

    private static ArrayList<Outline> outline(List<Element> kids, int level, String baseUri) {
        ArrayList<Outline> nodes = new ArrayList<Outline>();
        int index = 0;
        while (index < kids.size()) {
            Element element = kids.get(index);
            String tag = element.tagName();

            if ("details".equals(tag)) {
                String name = "卷";
                for (int c = 0; c < element.children().size(); c++) {
                    Element child = element.children().get(c);
                    if ("summary".equals(child.tagName())) {
                        name = text(child);
                        break;
                    }
                }
                ArrayList<Element> inner = new ArrayList<Element>();
                for (int c = 0; c < element.children().size(); c++) {
                    Element child = element.children().get(c);
                    if (!"summary".equals(child.tagName())) {
                        inner.add(child);
                    }
                }
                Outline volume = Outline.volume(name.length() == 0 ? "卷" : name, level);
                volume.children.addAll(outline(inner, level + 1, baseUri));
                nodes.add(volume);
                index++;
            } else if (isHeading(element)) {
                // a bare <summary> or a non-empty <p> owns everything up to the next heading
                String name = text(element);
                int end = index + 1;
                while (end < kids.size() && !isHeading(kids.get(end))) {
                    end++;
                }
                Outline volume = Outline.volume(name.length() == 0 ? "卷" : name, level);
                volume.children.addAll(outline(kids.subList(index + 1, end), level + 1, baseUri));
                nodes.add(volume);
                index = end;
            } else if ("a".equals(tag) && element.hasAttr("href")) {
                String label = element.attr("data-title").trim();
                if (label.length() == 0) {
                    label = text(element);
                }
                if (label.length() == 0) {
                    label = "未命名章节";
                }
                nodes.add(Outline.chapter(label, Site.canonical(baseUri, element.absUrl("href")),
                        level));
                index++;
            } else {
                index++;
            }
        }
        return nodes;
    }

    /** A heading starts a new volume; {@code <details>} counts so the scan can stop at it. */
    private static boolean isHeading(Element element) {
        String tag = element.tagName();
        if ("details".equals(tag) || "summary".equals(tag)) {
            return true;
        }
        return "p".equals(tag) && text(element).length() > 0;
    }

    private static String afterLabel(String line) {
        int colon = Math.max(line.indexOf('：'), line.indexOf(':'));
        return colon >= 0 ? line.substring(colon + 1).trim() : line;
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }

    private DetailParser() {
    }
}
