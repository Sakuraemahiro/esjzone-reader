package com.esjzone.reader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a downloaded book as an EPUB 3 archive.
 *
 * <p>Supports what the site actually produces: a cover, a synopsis page, a nesting table of
 * contents built from the detail page's volumes, illustrations embedded in the chapter flow, and
 * per-chapter placeholders when a chapter could not be fetched.</p>
 */
public final class EpubBuilder {

    public static final class Meta {
        public String title = "";
        public String author = "";
        public String genre = "";
        public String updated = "";
        public String description = "";
        public String sourceUrl = "";
        /** EPUB-internal file name of the cover, or "" when there is none. */
        public String coverFile = "";
    }

    public static final class Result {
        public int chapters;
        public int images;
        public int placeholders;
    }

    private static final long FIXED_TIME = 1262304000000L; // 2010-01-01, inside the ZIP epoch

    /**
     * @param outline  the detail page's volume tree, in reading order
     * @param contents chapter text keyed by chapter URL; a missing entry becomes a placeholder
     * @param images   EPUB-internal file name to a staged file, in manifest order
     */
    public static Result write(OutputStream out, Meta meta, ArrayList<Outline> outline,
                               HashMap<String, Chapter> contents,
                               LinkedHashMap<String, File> images) throws IOException {
        Result result = new Result();

        ArrayList<Outline> chapters = new ArrayList<Outline>();
        for (int i = 0; i < outline.size(); i++) {
            outline.get(i).collectChapters(chapters);
        }
        HashMap<Outline, String> fileNames = new HashMap<Outline, String>();
        for (int i = 0; i < chapters.size(); i++) {
            fileNames.put(chapters.get(i), String.format(Locale.US, "chap_%04d.xhtml", i + 1));
        }

        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16));
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        try {
            writeMimetype(zip);
            writeText(zip, "META-INF/container.xml", containerXml());
            writeText(zip, "OEBPS/stylesheet.css", stylesheet());

            if (meta.coverFile.length() > 0) {
                writeText(zip, "OEBPS/cover.xhtml", coverPage(meta));
            }
            if (meta.description.length() > 0) {
                writeText(zip, "OEBPS/description.xhtml", descriptionPage(meta));
            }

            for (int i = 0; i < chapters.size(); i++) {
                Outline chapter = chapters.get(i);
                Chapter body = contents.get(chapter.url);
                if (body == null) {
                    result.placeholders++;
                }
                writeText(zip, "OEBPS/" + fileNames.get(chapter),
                        chapterDocument(chapter.title, body));
                result.chapters++;
            }

            for (Map.Entry<String, File> entry : images.entrySet()) {
                writeFile(zip, "OEBPS/" + entry.getKey(), entry.getValue());
                result.images++;
            }

            writeText(zip, "OEBPS/nav.xhtml", navDocument(outline, fileNames, meta.title));
            writeText(zip, "OEBPS/toc.ncx", ncx(outline, fileNames, meta.title));
            writeText(zip, "OEBPS/content.opf",
                    packageDocument(meta, outline, chapters, fileNames, images));
            zip.finish();
        } finally {
            zip.close();
        }
        return result;
    }

    // ---------------------------------------------------------------- documents

    private static String chapterDocument(String title, Chapter body) {
        StringBuilder sb = new StringBuilder(4096);
        openXhtml(sb, title);
        sb.append("<h2 class=\"chapter\">");
        escape(sb, title);
        sb.append("</h2>\n");

        if (body == null) {
            sb.append("<p class=\"note\">本章节未能下载，可稍后重新下载本书补全。</p>\n");
        } else if (body.unavailable) {
            sb.append("<p class=\"note\">");
            escape(sb, body.note.length() > 0 ? body.note : "本章节无法获取");
            sb.append("</p>\n");
        }
        if (body != null && body.blocks.size() > 0) {
            for (int i = 0; i < body.blocks.size(); i++) {
                Chapter.Block block = body.blocks.get(i);
                if (block.image) {
                    // an illustration that could not be fetched leaves no tag behind
                    if (block.value.length() == 0) {
                        continue;
                    }
                    sb.append("<div class=\"pic\"><img src=\"")
                            .append(escapeAttribute(block.value)).append("\" alt=\"\"/></div>\n");
                } else {
                    sb.append("<p>");
                    escape(sb, block.value);
                    sb.append("</p>\n");
                }
            }
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String coverPage(Meta meta) {
        StringBuilder sb = new StringBuilder(512);
        openXhtml(sb, "封面");
        sb.append("<div class=\"cover\"><img src=\"")
                .append(escapeAttribute(meta.coverFile)).append("\" alt=\"封面\"/></div>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String descriptionPage(Meta meta) {
        StringBuilder sb = new StringBuilder(1024);
        openXhtml(sb, "简介");
        sb.append("<h2 class=\"chapter\">简介</h2>\n");
        String[] lines = meta.description.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().length() == 0) {
                continue;
            }
            sb.append("<p>");
            escape(sb, lines[i].trim());
            sb.append("</p>\n");
        }
        if (meta.genre.length() > 0 || meta.updated.length() > 0 || meta.sourceUrl.length() > 0) {
            sb.append("<p class=\"note\">");
            if (meta.genre.length() > 0) {
                escape(sb, meta.genre);
                sb.append(" · ");
            }
            if (meta.updated.length() > 0) {
                sb.append("更新日期 ");
                escape(sb, meta.updated);
            }
            if (meta.sourceUrl.length() > 0) {
                sb.append("<br/>");
                escape(sb, meta.sourceUrl);
            }
            sb.append("</p>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static void openXhtml(StringBuilder sb, String title) {
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n");
        sb.append("<head><meta charset=\"utf-8\"/><title>");
        escape(sb, title);
        sb.append("</title><link rel=\"stylesheet\" type=\"text/css\" href=\"stylesheet.css\"/></head>\n");
        sb.append("<body>\n");
    }

    private static String stylesheet() {
        return "body { margin: 0.6em; line-height: 1.7; }\n"
                + "p { margin: 0.4em 0; text-indent: 2em; }\n"
                + "h2.chapter { font-size: 1.15em; margin: 1.1em 0 0.7em 0; text-align: center;"
                + " font-weight: bold; line-height: 1.4; }\n"
                + "div.pic { text-align: center; margin: 0.8em 0; page-break-inside: avoid; }\n"
                + "div.pic img, div.cover img { max-width: 100%; height: auto; }\n"
                + "div.cover { text-align: center; margin: 0; padding: 0; }\n"
                + ".note { text-indent: 0; text-align: center; font-size: 0.85em; color: #888; }\n";
    }

    // ---------------------------------------------------------------- navigation

    private static String navDocument(ArrayList<Outline> outline,
                                      HashMap<Outline, String> fileNames, String title) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" "
                + "xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n");
        sb.append("<head><meta charset=\"utf-8\"/><title>目录</title></head>\n<body>\n");
        sb.append("<nav epub:type=\"toc\" id=\"toc\"><h2>目录</h2>\n");
        navList(sb, outline, fileNames, 1);
        sb.append("</nav>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static void navList(StringBuilder sb, ArrayList<Outline> nodes,
                                HashMap<Outline, String> fileNames, int depth) {
        sb.append("<ol>\n");
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            sb.append("<li>");
            if (node.volume) {
                sb.append("<span>");
                escape(sb, node.title);
                sb.append("</span>\n");
                navList(sb, node.children, fileNames, depth + 1);
            } else {
                String name = fileNames.get(node);
                sb.append("<a href=\"").append(name == null ? "nav.xhtml" : name).append("\">");
                escape(sb, node.title);
                sb.append("</a>");
            }
            sb.append("</li>\n");
        }
        sb.append("</ol>\n");
    }

    private static String ncx(ArrayList<Outline> outline,
                              HashMap<Outline, String> fileNames, String title) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n");
        sb.append("<head><meta name=\"dtb:uid\" content=\"");
        escape(sb, identifier(title));
        sb.append("\"/></head>\n<docTitle><text>");
        escape(sb, title);
        sb.append("</text></docTitle>\n<navMap>\n");
        int[] counter = new int[]{0};
        ncxPoints(sb, outline, fileNames, counter);
        sb.append("</navMap>\n</ncx>\n");
        return sb.toString();
    }

    private static void ncxPoints(StringBuilder sb, ArrayList<Outline> nodes,
                                  HashMap<Outline, String> fileNames, int[] counter) {
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            counter[0]++;
            sb.append("<navPoint id=\"nav").append(counter[0]).append("\" playOrder=\"")
                    .append(counter[0]).append("\">\n<navLabel><text>");
            escape(sb, node.title);
            sb.append("</text></navLabel>\n");
            if (node.volume) {
                ncxPoints(sb, node.children, fileNames, counter);
                String first = firstChapterFile(node, fileNames);
                sb.append("<content src=\"").append(first == null ? "nav.xhtml" : first)
                        .append("\"/>\n");
            } else {
                String name = fileNames.get(node);
                sb.append("<content src=\"").append(name == null ? "nav.xhtml" : name)
                        .append("\"/>\n");
            }
            sb.append("</navPoint>\n");
        }
    }

    private static String firstChapterFile(Outline volume, HashMap<Outline, String> fileNames) {
        for (int i = 0; i < volume.children.size(); i++) {
            Outline child = volume.children.get(i);
            if (!child.volume) {
                return fileNames.get(child);
            }
            String nested = firstChapterFile(child, fileNames);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- package

    private static String packageDocument(Meta meta, ArrayList<Outline> outline,
                                          ArrayList<Outline> chapters,
                                          HashMap<Outline, String> fileNames,
                                          LinkedHashMap<String, File> images) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" "
                + "unique-identifier=\"bookid\" xml:lang=\"zh-CN\">\n");
        sb.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
        sb.append("<dc:identifier id=\"bookid\">");
        escape(sb, identifier(meta.title));
        sb.append("</dc:identifier>\n<dc:title>");
        escape(sb, meta.title);
        sb.append("</dc:title>\n");
        if (meta.author.length() > 0) {
            sb.append("<dc:creator>");
            escape(sb, meta.author);
            sb.append("</dc:creator>\n");
        }
        sb.append("<dc:language>zh-CN</dc:language>\n");
        // readers show these in their book-info panel, so the synopsis has to be here too
        if (meta.genre.length() > 0) {
            sb.append("<dc:subject>");
            escape(sb, collapse(meta.genre));
            sb.append("</dc:subject>\n");
        }
        if (meta.description.length() > 0) {
            sb.append("<dc:description>");
            escape(sb, collapse(meta.description));
            sb.append("</dc:description>\n");
        }
        if (meta.sourceUrl.length() > 0) {
            sb.append("<dc:source>");
            escape(sb, meta.sourceUrl);
            sb.append("</dc:source>\n");
        }
        sb.append("<meta property=\"dcterms:modified\">").append(nowStamp()).append("</meta>\n");
        if (meta.coverFile.length() > 0) {
            // EPUB 2 readers look the cover up through this meta tag
            sb.append("<meta name=\"cover\" content=\"coverimage\"/>\n");
        }
        sb.append("</metadata>\n<manifest>\n");
        sb.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" "
                + "properties=\"nav\"/>\n");
        sb.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        sb.append("<item id=\"css\" href=\"stylesheet.css\" media-type=\"text/css\"/>\n");
        if (meta.coverFile.length() > 0) {
            sb.append("<item id=\"coverpage\" href=\"cover.xhtml\" "
                    + "media-type=\"application/xhtml+xml\"/>\n");
            sb.append("<item id=\"coverimage\" href=\"").append(escapeAttribute(meta.coverFile))
                    .append("\" media-type=\"").append(mediaType(meta.coverFile))
                    .append("\" properties=\"cover-image\"/>\n");
        }
        if (meta.description.length() > 0) {
            sb.append("<item id=\"desc\" href=\"description.xhtml\" "
                    + "media-type=\"application/xhtml+xml\"/>\n");
        }
        for (int i = 0; i < chapters.size(); i++) {
            sb.append("<item id=\"c").append(i + 1).append("\" href=\"")
                    .append(fileNames.get(chapters.get(i)))
                    .append("\" media-type=\"application/xhtml+xml\"/>\n");
        }
        int imageIndex = 0;
        for (Map.Entry<String, File> entry : images.entrySet()) {
            imageIndex++;
            sb.append("<item id=\"img").append(imageIndex).append("\" href=\"")
                    .append(escapeAttribute(entry.getKey())).append("\" media-type=\"")
                    .append(mediaType(entry.getKey())).append("\"/>\n");
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n");
        if (meta.coverFile.length() > 0) {
            sb.append("<itemref idref=\"coverpage\"/>\n");
        }
        if (meta.description.length() > 0) {
            sb.append("<itemref idref=\"desc\"/>\n");
        }
        for (int i = 0; i < chapters.size(); i++) {
            sb.append("<itemref idref=\"c").append(i + 1).append("\"/>\n");
        }
        sb.append("</spine>\n</package>\n");
        return sb.toString();
    }

    private static String mediaType(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/jpeg";
    }

    // ---------------------------------------------------------------- zip plumbing

    private static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] mime = "application/epub+zip".getBytes("US-ASCII");
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(mime.length);
        entry.setCompressedSize(mime.length);
        CRC32 crc = new CRC32();
        crc.update(mime);
        entry.setCrc(crc.getValue());
        entry.setTime(FIXED_TIME);
        zip.putNextEntry(entry);
        zip.write(mime);
        zip.closeEntry();
    }

    private static void writeText(ZipOutputStream zip, String name, String content)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        zip.putNextEntry(entry);
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static void writeFile(ZipOutputStream zip, String name, File source)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        zip.putNextEntry(entry);
        InputStream in = new FileInputStream(source);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        zip.closeEntry();
    }

    private static String containerXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                + "  <rootfiles>\n"
                + "    <rootfile full-path=\"OEBPS/content.opf\" "
                + "media-type=\"application/oebps-package+xml\"/>\n"
                + "  </rootfiles>\n"
                + "</container>\n";
    }

    private static String identifier(String title) {
        return "urn:uuid:" + UUID.nameUUIDFromBytes(("esjzone:" + title).getBytes()).toString();
    }

    private static String nowStamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    // ---------------------------------------------------------------- escaping

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static void escape(StringBuilder target, String text) {
        if (text == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    target.append("&amp;");
                    break;
                case '<':
                    target.append("&lt;");
                    break;
                case '>':
                    target.append("&gt;");
                    break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t') {
                        break;
                    }
                    target.append(c);
            }
        }
    }

    private static String escapeAttribute(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private EpubBuilder() {
    }
}
