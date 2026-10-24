package com.esjzone.reader;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a /forum/&lt;bookId&gt;/&lt;chapterId&gt;.html page.
 *
 * <p>The body lives in {@code div.forum-content.mt-3}, where paragraphs and illustrations
 * ({@code img.fr-fic.fr-dib}, from the Froala editor) are interleaved. Ordering is preserved so
 * that pictures stay where the author put them.</p>
 */
public final class ChapterParser {

    private static final Pattern POSTED =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");

    public static Chapter parse(Document doc, String baseUri) {
        Chapter chapter = new Chapter();

        Element heading = doc.selectFirst("h2");
        if (heading != null) {
            chapter.title = text(heading);
        }

        Element meta = doc.selectFirst(".single-post-meta");
        if (meta != null) {
            Element author = meta.selectFirst("a");
            if (author != null) {
                chapter.author = text(author);
            }
            Matcher stamp = POSTED.matcher(text(meta));
            if (stamp.find()) {
                chapter.posted = stamp.group();
            }
        }

        Element body = doc.selectFirst(".forum-content");
        if (body == null) {
            chapter.unavailable = true;
            chapter.note = doc.selectFirst("#oops") != null
                    ? "本章节被站方隐藏，需要换用举报页地址重试"
                    : "章节正文容器不存在，可能已被删除或需要重新登录";
            return chapter;
        }
        if (body.selectFirst(".btn-send-pw") != null) {
            chapter.unavailable = true;
            chapter.note = "本章节需要密码，已跳过";
            return chapter;
        }

        collect(body, chapter);
        if (chapter.blocks.isEmpty()) {
            chapter.unavailable = true;
            chapter.note = "本章节正文为空";
        }
        return chapter;
    }

    /** Chapter pages that were taken down answer with a notice page instead of the text. */
    public static boolean isNoticePage(Document doc) {
        Element body = doc.selectFirst(".forum-content");
        if (body == null) {
            return true;
        }
        String line = text(body);
        return line.length() < 40 && line.indexOf("送出") >= 0;
    }

    /** The mirror's older script reads the same chapter from a forum_report URL when it is gone. */
    public static String reportUrl(String chapterUrl) {
        return chapterUrl == null ? "" : chapterUrl.replace("/forum/", "/forum_report/");
    }

    public static Chapter parseReport(Document doc) {
        Chapter chapter = new Chapter();
        Element heading = doc.selectFirst("h2");
        if (heading != null) {
            chapter.title = text(heading);
        }
        Element form = doc.selectFirst(".forum-content-report");
        if (form == null) {
            chapter.unavailable = true;
            chapter.note = "本章节无法获取（已下架）";
            return chapter;
        }
        collect(form, chapter);
        if (chapter.blocks.isEmpty()) {
            chapter.unavailable = true;
            chapter.note = "本章节无法获取（已下架）";
        }
        return chapter;
    }

    private static void collect(Element parent, Chapter chapter) {
        Elements children = parent.children();
        for (int i = 0; i < children.size(); i++) {
            Element child = children.get(i);
            String tag = child.tagName();
            if ("img".equals(tag)) {
                String url = Site.canonical("", child.absUrl("src"));
                if (url.length() > 0) {
                    chapter.blocks.add(Chapter.Block.image(url));
                }
                continue;
            }
            if (child.selectFirst("img") != null) {
                // mixed text and pictures: descend so the original order survives
                collect(child, chapter);
                continue;
            }
            if ("p".equals(tag) || "div".equals(tag) || "h1".equals(tag)
                    || "h2".equals(tag) || "h3".equals(tag) || "blockquote".equals(tag)) {
                String line = text(child);
                if (line.length() > 0) {
                    chapter.blocks.add(Chapter.Block.text(line));
                }
            }
        }
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }

    private ChapterParser() {
    }
}
