import com.esjzone.reader.BookDetail;
import com.esjzone.reader.Chapter;
import com.esjzone.reader.ChapterParser;
import com.esjzone.reader.DetailParser;
import com.esjzone.reader.EpubBuilder;
import com.esjzone.reader.Outline;
import com.esjzone.reader.T2s;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Desktop harness: detail page + chapter pages in, finished EPUB out.
 *
 * <p>Stand-in for the download engine, so the packaging half can be verified offline:
 * EpubHarness &lt;out.epub&gt; &lt;t2s.tsv&gt; &lt;detail.html&gt; &lt;cover image&gt;
 * &lt;chapterUrl&gt;|&lt;chapter.html&gt; ...</p>
 */
public final class EpubHarness {

    public static void main(String[] args) throws Exception {
        String outPath = args[0];
        T2s t2s = T2s.load(new File(args[1]));
        File detailFile = new File(args[2]);
        File coverSource = new File(args[3]);

        String detailBase = "https://www.esjzone.one/detail/1744349889.html";
        Document detailDoc = Jsoup.parse(detailFile, "UTF-8", detailBase);
        BookDetail detail = DetailParser.parse(detailDoc, detailBase);

        EpubBuilder.Meta meta = new EpubBuilder.Meta();
        meta.title = t2s.convert(detail.title);
        meta.author = t2s.convert(detail.author);
        meta.genre = t2s.convert(detail.genre);
        meta.updated = detail.updated;
        meta.description = t2s.convert(detail.description);
        meta.sourceUrl = detailBase;
        meta.coverFile = "images/cover.png";

        LinkedHashMap<String, File> images = new LinkedHashMap<String, File>();
        images.put(meta.coverFile, coverSource);

        HashMap<String, Chapter> contents = new HashMap<String, Chapter>();
        int staged = 0;
        for (int i = 4; i < args.length; i++) {
            String[] parts = args[i].split("\\|", 2);
            String url = parts[0];
            Document doc = Jsoup.parse(new File(parts[1]), "UTF-8", url);
            Chapter chapter = ChapterParser.parse(doc, url);
            chapter.title = t2s.convert(chapter.title);
            chapter.note = t2s.convert(chapter.note);
            chapter.author = t2s.convert(chapter.author);

            // re-point illustrations at staged files, exactly as the downloader will
            for (int b = 0; b < chapter.blocks.size(); b++) {
                Chapter.Block block = chapter.blocks.get(b);
                if (block.image) {
                    staged++;
                    String name = String.format("images/img_%04d.png", staged);
                    images.put(name, coverSource);
                    chapter.blocks.set(b, Chapter.Block.image(name));
                } else {
                    chapter.blocks.set(b, Chapter.Block.text(t2s.convert(block.value)));
                }
            }
            contents.put(url, chapter);
            System.out.println("  staged " + url + " -> " + chapter.blocks.size() + " blocks");
        }

        System.out.println("outline: " + detail.outline.size() + " top-level volumes, "
                + detail.chapterCount() + " chapters");
        for (int i = 0; i < detail.outline.size(); i++) {
            Outline volume = detail.outline.get(i);
            System.out.println("  [卷] " + t2s.convert(volume.title) + " (" + volume.chapterCount() + ")");
        }

        OutputStream out = new FileOutputStream(outPath);
        EpubBuilder.Result result;
        try {
            result = EpubBuilder.write(out, meta, detail.outline, contents, images);
        } finally {
            out.close();
        }
        System.out.println("EPUB: " + outPath + " (" + new File(outPath).length() + " bytes)");
        System.out.println("  chapters=" + result.chapters + " images=" + result.images
                + " placeholders=" + result.placeholders);
    }

    private EpubHarness() {
    }
}
