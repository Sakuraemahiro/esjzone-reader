package com.esjzone.reader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads an EPUB from disk and exports a subset of its chapters as a new EPUB.
 *
 * <p>Everything is copied byte for byte at its original path, so the chapter documents keep
 * working: internal links, stylesheets and image references all still resolve. Only the package
 * document and the two tables of contents are regenerated.</p>
 */
public final class EpubFile {

    public static final class Chapter {
        public String title = "";
        public String href = "";
        /** Uncompressed size of the document plus the images it references. */
        public long bytes;
        public int images;
        public final ArrayList<String> imageHrefs = new ArrayList<String>();
    }

    public static final class Book {
        public String title = "";
        public String author = "";
        public String opfPath = "";
        public String version = "3.0";
        public String coverHref = "";
        public String description = "";
        public String subject = "";
        public String sourceUrl = "";
        public final ArrayList<Chapter> chapters = new ArrayList<Chapter>();
        /** Every manifest item: href to media type. */
        public final LinkedHashMap<String, String> manifest = new LinkedHashMap<String, String>();
        public long totalBytes;

        public Chapter chapterAt(int index) {
            return chapters.get(index);
        }
    }

    // ---------------------------------------------------------------- reading

    public static Book read(File file) throws IOException {
        Book book = new Book();
        ZipFile zip = new ZipFile(file);
        try {
            String opfPath = containerOpf(zip);
            if (opfPath == null) {
                throw new IOException("这个 EPUB 缺少 container.xml，无法解析");
            }
            book.opfPath = opfPath;
            String opfDir = directoryOf(opfPath);

            Document opf = parseXml(read(zip, opfPath));
            book.version = opf.selectFirst("package") == null ? "3.0"
                    : opf.selectFirst("package").attr("version");

            book.title = firstText(opf, "dc:title", "title");
            book.author = firstText(opf, "dc:creator", "creator");
            book.description = firstText(opf, "dc:description", "description");
            book.subject = firstText(opf, "dc:subject", "subject");
            book.sourceUrl = firstText(opf, "dc:source", "source");

            HashMap<String, String> idToHref = new HashMap<String, String>();
            Elements items = opf.select("manifest item");
            for (int i = 0; i < items.size(); i++) {
                Element item = items.get(i);
                String href = item.attr("href");
                if (href.length() == 0) {
                    continue;
                }
                String full = resolve(opfDir, href);
                idToHref.put(item.attr("id"), full);
                book.manifest.put(full, item.attr("media-type"));
            }

            HashMap<String, String> titles = readTitles(zip, opf, opfDir);
            HashSet<String> counted = new HashSet<String>();

            // cover: EPUB 3 marks it with properties="cover-image", EPUB 2 with a meta tag
            for (int i = 0; i < items.size(); i++) {
                Element item = items.get(i);
                if (item.attr("properties").contains("cover-image")) {
                    book.coverHref = resolve(opfDir, item.attr("href"));
                    break;
                }
            }
            if (book.coverHref.length() == 0) {
                Elements metas = opf.select("meta[name=cover]");
                if (!metas.isEmpty()) {
                    String href = idToHref.get(metas.get(0).attr("content"));
                    if (href != null) {
                        book.coverHref = href;
                    }
                }
            }

            Elements spine = opf.select("spine itemref");
            for (int i = 0; i < spine.size(); i++) {
                String idref = spine.get(i).attr("idref");
                String href = idToHref.get(idref);
                if (href == null) {
                    continue;
                }
                ZipEntry entry = zip.getEntry(href);
                if (entry == null) {
                    continue;
                }
                Chapter chapter = new Chapter();
                chapter.href = href;
                chapter.bytes = entry.getSize() > 0 ? entry.getSize() : 0;
                String label = titles.get(href);
                if (label == null || label.length() == 0) {
                    label = titleFromDocument(read(zip, href));
                }
                chapter.title = label.length() > 0 ? label : ("第 " + (i + 1) + " 节");
                collectImages(zip, opfDir, href, chapter, counted);
                book.chapters.add(chapter);
            }

            book.totalBytes = file.length();
        } finally {
            zip.close();
        }
        return book;
    }

    /** Titles from nav.xhtml (EPUB 3) or toc.ncx (EPUB 2), keyed by absolute path. */
    private static HashMap<String, String> readTitles(ZipFile zip, Document opf, String opfDir) {
        HashMap<String, String> titles = new HashMap<String, String>();
        String navHref = null;
        String ncxHref = null;
        Elements items = opf.select("manifest item");
        for (int i = 0; i < items.size(); i++) {
            Element item = items.get(i);
            String type = item.attr("media-type");
            if ("application/xhtml+xml".equals(type) && item.attr("properties").contains("nav")) {
                navHref = resolve(opfDir, item.attr("href"));
            }
            if ("application/x-dtbncx+xml".equals(type)) {
                ncxHref = resolve(opfDir, item.attr("href"));
            }
        }
        if (navHref != null) {
            try {
                String navDir = directoryOf(navHref);
                Document nav = parseXml(read(zip, navHref));
                Elements links = nav.select("nav a[href]");
                for (int i = 0; i < links.size(); i++) {
                    Element link = links.get(i);
                    String href = resolve(navDir, stripFragment(link.attr("href")));
                    String text = link.text().trim();
                    if (href.length() > 0 && text.length() > 0 && !titles.containsKey(href)) {
                        titles.put(href, text);
                    }
                }
            } catch (Exception ignored) {
                // fall back to the ncx or the document itself
            }
        }
        if (ncxHref != null && titles.isEmpty()) {
            try {
                String ncxDir = directoryOf(ncxHref);
                Document ncx = parseXml(read(zip, ncxHref));
                Elements points = ncx.select("navPoint");
                for (int i = 0; i < points.size(); i++) {
                    Element point = points.get(i);
                    Element label = point.selectFirst("navLabel text");
                    Element content = point.selectFirst("content");
                    if (label == null || content == null) {
                        continue;
                    }
                    String href = resolve(ncxDir, stripFragment(content.attr("src")));
                    if (href.length() > 0 && !titles.containsKey(href)) {
                        titles.put(href, label.text().trim());
                    }
                }
            } catch (Exception ignored) {
                // the document titles are used instead
            }
        }
        return titles;
    }

    /** Adds the size of every image a chapter references. */
    private static void collectImages(ZipFile zip, String opfDir, String chapterHref,
                                      Chapter chapter, HashSet<String> counted) {
        String content;
        try {
            content = read(zip, chapterHref);
        } catch (Exception error) {
            return;
        }
        String dir = directoryOf(chapterHref);
        Document doc = Jsoup.parse(content, "", Parser.xmlParser());
        Elements images = doc.select("img[src], image");
        HashSet<String> local = new HashSet<String>();
        for (int i = 0; i < images.size(); i++) {
            Element image = images.get(i);
            String src = image.attr("src");
            if (src.length() == 0) {
                src = image.attr("xlink:href");
            }
            if (src.length() == 0 || src.startsWith("http") || src.startsWith("data:")) {
                continue;
            }
            String href = resolve(dir, stripFragment(src));
            if (!local.add(href)) {
                continue;
            }
            ZipEntry entry = zip.getEntry(href);
            if (entry == null) {
                continue;
            }
            chapter.bytes += Math.max(0, entry.getSize());
            chapter.images++;
            chapter.imageHrefs.add(href);
            counted.add(href);
        }
    }

    // ---------------------------------------------------------------- export

    /**
     * Writes a new EPUB containing only the chosen chapters.
     *
     * @param nameForEntry mapper for the chapter document name, so per-chapter exports can drop
     *                     the original file name
     */
    public static void export(File source, Book book, ArrayList<Chapter> chosen, String title,
                              OutputStream out) throws IOException {
        ZipFile zip = new ZipFile(source);
        try {
            ZipOutputStream target = new ZipOutputStream(out);
            target.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
            String opfDir = directoryOf(book.opfPath);
            try {
                writeMimetype(target);
                target.putNextEntry(new ZipEntry("META-INF/container.xml"));
                target.write(containerXml(book.opfPath).getBytes("UTF-8"));
                target.closeEntry();

                // images referenced by the chosen chapters, plus their stylesheets
                LinkedHashMap<String, String> extras = new LinkedHashMap<String, String>();
                HashSet<String> seen = new HashSet<String>();
                for (int i = 0; i < chosen.size(); i++) {
                    Chapter chapter = chosen.get(i);
                    for (int h = 0; h < chapter.imageHrefs.size(); h++) {
                        String href = chapter.imageHrefs.get(h);
                        if (seen.add(href)) {
                            extras.put(href, mediaType(href));
                        }
                    }
                    collectStylesheets(zip, chapter.href, extras, seen);
                }

                if (book.coverHref.length() > 0 && zip.getEntry(book.coverHref) != null
                        && seen.add(book.coverHref)) {
                    extras.put(book.coverHref, mediaType(book.coverHref));
                }

                // chapter documents, copied verbatim
                for (int i = 0; i < chosen.size(); i++) {
                    copyEntry(zip, target, chosen.get(i).href);
                }
                for (String href : extras.keySet()) {
                    copyEntry(zip, target, href);
                }

                // a fresh archive has no nav or ncx yet, so fixed names are safe here
                String navPath = opfDir.length() == 0 ? "nav.xhtml" : opfDir + "/nav.xhtml";
                String ncxPath = opfDir.length() == 0 ? "toc.ncx" : opfDir + "/toc.ncx";
                writeEntry(target, navPath, nav(book, chosen, title, navPath, chosen.get(0).href));
                writeEntry(target, ncxPath, ncx(book, chosen, title, ncxPath));
                writeEntry(target, book.opfPath,
                        packageDocument(book, chosen, title, extras, opfDir, navPath, ncxPath));
                target.finish();
            } finally {
                target.close();
            }
        } finally {
            zip.close();
        }
    }

    private static void collectStylesheets(ZipFile zip, String chapterHref,
                                           LinkedHashMap<String, String> extras,
                                           HashSet<String> seen) {
        String content;
        try {
            content = read(zip, chapterHref);
        } catch (Exception error) {
            return;
        }
        String dir = directoryOf(chapterHref);
        Document doc = Jsoup.parse(content, "", Parser.xmlParser());
        Elements links = doc.select("link[rel=stylesheet][href]");
        for (int i = 0; i < links.size(); i++) {
            String href = resolve(dir, stripFragment(links.get(i).attr("href")));
            if (href.length() == 0 || seen.contains(href) || zip.getEntry(href) == null) {
                continue;
            }
            seen.add(href);
            extras.put(href, "text/css");
        }
    }

    private static String packageDocument(Book book, ArrayList<Chapter> chosen, String title,
                                          LinkedHashMap<String, String> extras, String opfDir,
                                          String navPath, String ncxPath) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" ")
                .append("unique-identifier=\"bookid\">\n");
        sb.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");
        sb.append("<dc:identifier id=\"bookid\">urn:uuid:")
                .append(java.util.UUID.nameUUIDFromBytes(title.getBytes()))
                .append("</dc:identifier>\n");
        sb.append("<dc:title>").append(escape(title)).append("</dc:title>\n");
        if (book.author.length() > 0) {
            sb.append("<dc:creator>").append(escape(book.author)).append("</dc:creator>\n");
        }
        sb.append("<dc:language>zh-CN</dc:language>\n");
        // carried over from the source so readers keep showing the synopsis
        if (book.subject.length() > 0) {
            sb.append("<dc:subject>").append(escape(collapse(book.subject)))
                    .append("</dc:subject>\n");
        }
        if (book.description.length() > 0) {
            sb.append("<dc:description>").append(escape(collapse(book.description)))
                    .append("</dc:description>\n");
        }
        if (book.sourceUrl.length() > 0) {
            sb.append("<dc:source>").append(escape(book.sourceUrl)).append("</dc:source>\n");
        }
        sb.append("<meta property=\"dcterms:modified\">2020-01-01T00:00:00Z</meta>\n");
        sb.append("</metadata>\n<manifest>\n");
        sb.append("<item id=\"nav\" href=\"").append(relative(opfDir, navPath))
                .append("\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        sb.append("<item id=\"ncx\" href=\"").append(relative(opfDir, ncxPath))
                .append("\" media-type=\"application/x-dtbncx+xml\"/>\n");
        for (int i = 0; i < chosen.size(); i++) {
            sb.append("<item id=\"c").append(i + 1).append("\" href=\"")
                    .append(relative(opfDir, chosen.get(i).href))
                    .append("\" media-type=\"application/xhtml+xml\"/>\n");
        }
        int index = 0;
        for (String href : extras.keySet()) {
            index++;
            sb.append("<item id=\"x").append(index).append("\" href=\"")
                    .append(relative(opfDir, href)).append("\" media-type=\"")
                    .append(extras.get(href)).append("\"");
            if (href.equals(book.coverHref)) {
                sb.append(" properties=\"cover-image\"");
            }
            sb.append("/>\n");
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n");
        for (int i = 0; i < chosen.size(); i++) {
            sb.append("<itemref idref=\"c").append(i + 1).append("\"/>\n");
        }
        sb.append("</spine>\n</package>\n");
        return sb.toString();
    }

    private static String nav(Book book, ArrayList<Chapter> chosen, String title,
                              String navPath, String firstChapter) {
        String navDir = directoryOf(navPath);
        StringBuilder sb = new StringBuilder(1024 + chosen.size() * 80);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" ")
                .append("xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-CN\">\n");
        sb.append("<head><meta charset=\"utf-8\"/><title>").append(escape(title))
                .append("</title></head>\n<body>\n");
        sb.append("<nav epub:type=\"toc\" id=\"toc\"><h2>目录</h2>\n<ol>\n");
        for (int i = 0; i < chosen.size(); i++) {
            Chapter chapter = chosen.get(i);
            sb.append("<li><a href=\"").append(relative(navDir, chapter.href)).append("\">")
                    .append(escape(chapter.title)).append("</a></li>\n");
        }
        sb.append("</ol>\n</nav>\n</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * Builds toc.ncx.
     *
     * <p>Every {@code content src} must be relative to the NCX file itself, not to the archive
     * root. Emitting "OEBPS/chap_0001.xhtml" from inside "OEBPS/toc.ncx" leaves every entry
     * unresolvable, and a reader that drives navigation from the NCX then shows empty chapters
     * with the real text bunched into the last one.</p>
     */
    private static String ncx(Book book, ArrayList<Chapter> chosen, String title, String ncxPath) {
        String ncxDir = directoryOf(ncxPath);
        StringBuilder sb = new StringBuilder(1024 + chosen.size() * 120);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n");
        sb.append("<head><meta name=\"dtb:uid\" content=\"esjzone-split\"/></head>\n");
        sb.append("<docTitle><text>").append(escape(title)).append("</text></docTitle>\n<navMap>\n");
        for (int i = 0; i < chosen.size(); i++) {
            Chapter chapter = chosen.get(i);
            sb.append("<navPoint id=\"np").append(i + 1).append("\" playOrder=\"").append(i + 1)
                    .append("\"><navLabel><text>").append(escape(chapter.title))
                    .append("</text></navLabel><content src=\"")
                    .append(escape(relative(ncxDir, chapter.href)))
                    .append("\"/></navPoint>\n");
        }
        sb.append("</navMap>\n</ncx>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- zip helpers

    private static String containerOpf(ZipFile zip) throws IOException {
        String xml = read(zip, "META-INF/container.xml");
        if (xml.length() == 0) {
            return null;
        }
        Document doc = parseXml(xml);
        Elements roots = doc.select("rootfile");
        if (roots.isEmpty()) {
            return null;
        }
        String path = roots.get(0).attr("full-path");
        return path.length() > 0 ? path : null;
    }

    private static void writeMimetype(ZipOutputStream target) throws IOException {
        byte[] mime = "application/epub+zip".getBytes("US-ASCII");
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(mime.length);
        entry.setCompressedSize(mime.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(mime);
        entry.setCrc(crc.getValue());
        target.putNextEntry(entry);
        target.write(mime);
        target.closeEntry();
    }

    private static void copyEntry(ZipFile zip, ZipOutputStream target, String path)
            throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return;
        }
        ZipEntry out = new ZipEntry(path);
        out.setTime(entry.getTime());
        target.putNextEntry(out);
        InputStream in = zip.getInputStream(entry);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                target.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        target.closeEntry();
    }

    private static void writeEntry(ZipOutputStream target, String path, String content)
            throws IOException {
        ZipEntry entry = new ZipEntry(path);
        target.putNextEntry(entry);
        target.write(content.getBytes("UTF-8"));
        target.closeEntry();
    }

    private static String read(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return "";
        }
        InputStream in = zip.getInputStream(entry);
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(
                    (int) Math.max(1024, Math.min(entry.getSize(), 1 << 20)));
            byte[] chunk = new byte[32 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String uniquePath(ZipFile zip, String dir, String name) {
        String candidate = dir.length() == 0 ? name : dir + "/" + name;
        if (zip.getEntry(candidate) == null) {
            return candidate;
        }
        String base = candidate.substring(0, candidate.lastIndexOf('.'));
        int index = 1;
        while (zip.getEntry(base + "_" + index + ".xhtml") != null) {
            index++;
        }
        return base + "_" + index + ".xhtml";
    }

    // ---------------------------------------------------------------- misc

    private static Document parseXml(String xml) {
        return Jsoup.parse(xml, "", Parser.xmlParser());
    }

    private static String firstText(Document doc, String... tags) {
        for (int i = 0; i < tags.length; i++) {
            Elements found = doc.getElementsByTag(tags[i]);
            if (!found.isEmpty()) {
                String text = found.get(0).text().trim();
                if (text.length() > 0) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String titleFromDocument(String content) {
        if (content.length() == 0) {
            return "";
        }
        Document doc = parseXml(content);
        for (String tag : new String[]{"title", "h1", "h2", "h3"}) {
            Elements found = doc.getElementsByTag(tag);
            if (!found.isEmpty()) {
                String text = found.get(0).text().trim();
                if (text.length() > 0) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String mediaType(String href) {
        String lower = href.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        return "image/jpeg";
    }

    static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String resolve(String baseDir, String href) {
        if (href == null) {
            return "";
        }
        String path = href;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String combined = baseDir.length() == 0 ? path : baseDir + "/" + path;
        ArrayList<String> parts = new ArrayList<String>();
        String[] pieces = combined.split("/");
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.length() == 0 || ".".equals(piece)) {
                continue;
            }
            if ("..".equals(piece)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(piece);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Path of {@code target} relative to the directory {@code fromDir}. */
    static String relative(String fromDir, String target) {
        if (fromDir.length() == 0) {
            return target;
        }
        String[] from = fromDir.split("/");
        String[] to = target.split("/");
        int common = 0;
        while (common < from.length && common < to.length && from[common].equals(to[common])) {
            common++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < from.length; i++) {
            sb.append("../");
        }
        for (int i = common; i < to.length; i++) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            sb.append(to[i]);
        }
        return sb.toString();
    }

    private static String stripFragment(String href) {
        int hash = href.indexOf('#');
        return hash < 0 ? href : href.substring(0, hash);
    }

    private static String containerXml(String opfPath) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<container version=\"1.0\" "
                + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                + "  <rootfiles>\n"
                + "    <rootfile full-path=\"" + opfPath
                + "\" media-type=\"application/oebps-package+xml\"/>\n"
                + "  </rootfiles>\n</container>\n";
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private EpubFile() {
    }
}
