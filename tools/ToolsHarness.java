import com.esjzone.reader.EpubFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Desktop harness for the offline splitter.
 *
 * <p>Usage: ToolsHarness &lt;in.epub&gt; &lt;merged.epub&gt; &lt;single.epub&gt; &lt;chapterIndex&gt;</p>
 */
public final class ToolsHarness {

    public static void main(String[] args) throws Exception {
        File source = new File(args[0]);
        EpubFile.Book book = EpubFile.read(source);

        System.out.println("source      : " + source.getName() + "  " + source.length() + " bytes");
        System.out.println("title       : " + book.title);
        System.out.println("author      : " + book.author);
        System.out.println("opf         : " + book.opfPath + "  version " + book.version);
        System.out.println("manifest    : " + book.manifest.size() + " items");
        System.out.println("chapters    : " + book.chapters.size());

        long total = 0;
        int withImages = 0;
        for (int i = 0; i < book.chapters.size(); i++) {
            EpubFile.Chapter chapter = book.chapters.get(i);
            total += chapter.bytes;
            if (chapter.images > 0) {
                withImages++;
            }
            if (i < 6 || i >= book.chapters.size() - 3) {
                System.out.println(String.format("  [%3d] %-42s %8d B  %2d img  %s",
                        i, shorten(chapter.title, 40), chapter.bytes, chapter.images,
                        chapter.href));
            }
        }
        System.out.println("sum of chapter sizes: " + total + " bytes");
        System.out.println("chapters with images: " + withImages);

        // a merged export of four chapters
        ArrayList<EpubFile.Chapter> few = new ArrayList<EpubFile.Chapter>();
        for (int i = 1; i < Math.min(5, book.chapters.size()); i++) {
            few.add(book.chapters.get(i));
        }
        OutputStream merged = new FileOutputStream(args[1]);
        try {
            EpubFile.export(source, book, few, "合并测试", merged);
        } finally {
            merged.close();
        }
        System.out.println("merged export : " + args[1] + "  "
                + new File(args[1]).length() + " bytes, " + few.size() + " chapters");

        // a single-chapter export
        int index = Integer.parseInt(args[3]);
        ArrayList<EpubFile.Chapter> one = new ArrayList<EpubFile.Chapter>();
        one.add(book.chapters.get(index));
        OutputStream single = new FileOutputStream(args[2]);
        try {
            EpubFile.export(source, book, one, "单章测试", single);
        } finally {
            single.close();
        }
        System.out.println("single export : " + args[2] + "  "
                + new File(args[2]).length() + " bytes, chapter " + index
                + " (" + book.chapters.get(index).title + ")");
    }

    private static String shorten(String text, int limit) {
        return text == null ? "" : (text.length() <= limit ? text : text.substring(0, limit) + "…");
    }

    private ToolsHarness() {
    }
}
