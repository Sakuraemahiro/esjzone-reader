import com.esjzone.reader.Chapter;
import com.esjzone.reader.EpubBuilder;
import com.esjzone.reader.EpubFile;
import com.esjzone.reader.Outline;
import com.esjzone.reader.T2s;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Verifies that the synopsis reaches the OPF metadata on both paths:
 * the download writer (EpubBuilder) and the split writer (EpubFile).
 */
public final class MetaHarness {

    public static void main(String[] args) throws Exception {
        File built = new File(args[0]);
        File split = new File(args[1]);
        String description = "测试简介第一段。\n第二段比较长，用来确认换行会被折叠成空格，"
                + "并且 & < > 这些字符会被正确转义。";

        // ---- 1. what the downloader produces
        EpubBuilder.Meta meta = new EpubBuilder.Meta();
        meta.title = "元数据测试书";
        meta.author = "作者甲";
        meta.genre = "韓輕";
        meta.updated = "2026-01-02";
        meta.description = description;
        meta.sourceUrl = "https://www.esjzone.one/detail/1784800969.html";

        ArrayList<Outline> outline = new ArrayList<Outline>();
        HashMap<String, Chapter> contents = new HashMap<String, Chapter>();
        Outline volume = Outline.volume("正文", 0);
        for (int i = 1; i <= 3; i++) {
            Outline chapter = Outline.chapter("第" + i + "话", "https://example.invalid/c" + i, 1);
            volume.children.add(chapter);
            Chapter body = new Chapter();
            body.title = "第" + i + "话";
            body.blocks.add(Chapter.Block.text("这是第 " + i + " 话的正文。"));
            contents.put(chapter.url, body);
        }
        outline.add(volume);

        LinkedHashMap<String, File> images = new LinkedHashMap<String, File>();
        OutputStream out = new FileOutputStream(built);
        try {
            EpubBuilder.Result result = EpubBuilder.write(out, meta, outline, contents, images);
            System.out.println("built: " + result.chapters + " chapters -> " + built.length() + " bytes");
        } finally {
            out.close();
        }

        showMetadata("DOWNLOAD OUTPUT", built);

        // ---- 2. read it back and re-export one chapter (the split path)
        EpubFile.Book book = EpubFile.read(built);
        System.out.println();
        System.out.println("read back: title=" + book.title
                + "  author=" + book.author
                + "  subject=" + book.subject);
        System.out.println("           description=" + shorten(book.description, 60));
        System.out.println("           source=" + book.sourceUrl);

        ArrayList<EpubFile.Chapter> one = new ArrayList<EpubFile.Chapter>();
        one.add(book.chapters.get(1));
        OutputStream out2 = new FileOutputStream(split);
        try {
            EpubFile.export(built, book, one, "拆分后的书", out2);
        } finally {
            out2.close();
        }
        showMetadata("SPLIT OUTPUT", split);
    }

    private static void showMetadata(String label, File epub) throws Exception {
        System.out.println();
        System.out.println("--- " + label + " (" + epub.length() + " bytes) ---");
        ZipFile zip = new ZipFile(epub);
        try {
            String opfPath = null;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".opf")) {
                    opfPath = entry.getName();
                }
            }
            String opf = read(zip, opfPath);
            for (String tag : new String[]{"dc:title", "dc:creator", "dc:subject",
                    "dc:description", "dc:source", "<meta name=\"cover\"", "cover-image"}) {
                int at = opf.indexOf(tag);
                if (at < 0) {
                    System.out.println("  " + pad(tag) + " MISSING");
                    continue;
                }
                String tail = opf.substring(at, Math.min(opf.length(), at + 220));
                System.out.println("  " + pad(tag) + collapse(tail));
            }
        } finally {
            zip.close();
        }
    }

    private static String read(ZipFile zip, String path) throws Exception {
        InputStream in = zip.getInputStream(zip.getEntry(path));
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String pad(String text) {
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < 24) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String text, int limit) {
        return text == null ? "" : (text.length() <= limit ? text : text.substring(0, limit) + "…");
    }

    private MetaHarness() {
    }
}
