import com.esjzone.reader.Book;
import com.esjzone.reader.BookDetail;
import com.esjzone.reader.Chapter;
import com.esjzone.reader.ChapterParser;
import com.esjzone.reader.DetailParser;
import com.esjzone.reader.FavoritesParser;
import com.esjzone.reader.Outline;
import com.esjzone.reader.T2s;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;

/**
 * Desktop harness: runs the app's parsers against saved pages so the whole parsing layer can be
 * checked without a device.
 *
 * <p>Usage: ParserHarness &lt;report file&gt; &lt;t2s.tsv&gt; &lt;page&gt;:&lt;mode&gt;:&lt;baseUri&gt; ...</p>
 */
public final class ParserHarness {

    private static PrintStream out;
    private static T2s converter;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new File(args[0]), "UTF-8");
        converter = T2s.load(new File(args[1]));
        out.println("t2s entries loaded: " + converter.size());
        out.println("t2s smoke test: " + converter.convert("這個問題與萬歲的頭髮，後臺資訊"));
        out.println();

        for (int i = 2; i < args.length; i++) {
            String[] parts = args[i].split("\\|", 3);
            String mode = parts[0];
            String path = parts[1];
            String baseUri = parts[2];
            out.println("=== " + mode + " :: " + new File(path).getName());
            try {
                Document doc = Jsoup.parse(new File(path), "UTF-8", baseUri);
                if ("favorites".equals(mode)) {
                    favorites(doc, baseUri);
                } else if ("detail".equals(mode)) {
                    detail(doc, baseUri);
                } else {
                    chapter(doc, baseUri);
                }
            } catch (Exception error) {
                out.println("  FAILED: " + error);
            }
            out.println();
        }
        out.close();
        System.out.println("report written: " + args[0]);
    }

    private static void favorites(Document doc, String baseUri) throws Exception {
        ArrayList<Book> books = FavoritesParser.parse(doc, baseUri);
        out.println("  books: " + books.size());
        out.println("  page count: " + FavoritesParser.pageCount(doc.html()));
        out.println("  sorting: " + format(FavoritesParser.sortingOptions(doc.html())));
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            out.println(String.format("  %2d. %s", i + 1, book.title));
            out.println("      id=" + book.id + "  updated=" + book.updated);
            out.println("      detail=" + book.detailUrl);
            out.println("      latest=" + book.latestTitle + " -> " + book.latestUrl);
            out.println("      viewed=" + book.lastViewed);
        }
        boolean dead = false;
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).detailUrl.contains("esjzone.cc")
                    || books.get(i).latestUrl.contains("esjzone.cc")
                    || books.get(i).latestUrl.contains("esjzone.me")) {
                dead = true;
            }
        }
        out.println("  stale-domain links left: " + dead);
    }

    private static void detail(Document doc, String baseUri) throws Exception {
        out.println("  looksLikeDetail: " + DetailParser.looksLikeDetail(doc));
        BookDetail book = DetailParser.parse(doc, baseUri);
        out.println("  title   : " + book.title);
        out.println("  id      : " + book.id);
        out.println("  author  : " + book.author);
        out.println("  genre   : " + book.genre);
        out.println("  updated : " + book.updated);
        out.println("  extra   : " + book.extra);
        out.println("  cover   : " + book.coverUrl);
        out.println("  summary : " + shorten(book.description, 70));
        out.println("  outline :");
        showOutline(book.outline, 2);

        ArrayList<Outline> chapters = new ArrayList<Outline>();
        for (int i = 0; i < book.outline.size(); i++) {
            book.outline.get(i).collectChapters(chapters);
        }
        int anchors = doc.select("#chapterList a[href]").size();
        out.println("  chapters=" + chapters.size() + "  anchors=" + anchors
                + "  -> " + (chapters.size() == anchors ? "MATCH" : "MISMATCH"));
        out.println("  chapterCount()=" + book.chapterCount());
        out.println("  blank titles=" + countBlank(chapters)
                + "  non-forum=" + countNonForum(chapters));
        if (!book.title.isEmpty()) {
            out.println("  converted title: " + converter.convert(book.title));
            out.println("  converted author: " + converter.convert(book.author));
        }
    }

    private static void chapter(Document doc, String baseUri) throws Exception {
        Chapter chapter = ChapterParser.parse(doc, baseUri);
        out.println("  title      : " + chapter.title);
        out.println("  converted  : " + converter.convert(chapter.title));
        out.println("  author     : " + chapter.author + "   posted=" + chapter.posted);
        out.println("  unavailable: " + chapter.unavailable + "  note=" + chapter.note);
        out.println("  blocks     : " + chapter.blocks.size()
                + "  text=" + chapter.textLength() + "  images=" + chapter.imageCount());
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < chapter.blocks.size() && i < 10; i++) {
            order.append(chapter.blocks.get(i).image ? "IMG " : "txt ");
        }
        out.println("  order      : " + order);
        for (int i = 0; i < chapter.blocks.size(); i++) {
            Chapter.Block block = chapter.blocks.get(i);
            if (block.image) {
                out.println("      [image] " + block.value);
            }
        }
        if (chapter.textLength() > 0) {
            StringBuilder sample = new StringBuilder();
            for (int i = 0; i < chapter.blocks.size() && sample.length() < 90; i++) {
                if (!chapter.blocks.get(i).image) {
                    sample.append(chapter.blocks.get(i).value).append(' ');
                }
            }
            out.println("      [text ] " + shorten(converter.convert(sample.toString()), 90));
        }
        out.println("  isNoticePage: " + ChapterParser.isNoticePage(doc));
        out.println("  reportUrl   : " + ChapterParser.reportUrl(baseUri));
    }

    private static void showOutline(ArrayList<Outline> nodes, int indent) {
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            StringBuilder pad = new StringBuilder();
            for (int p = 0; p < indent; p++) {
                pad.append("  ");
            }
            if (node.volume) {
                out.println(pad + "[卷] " + node.title + "  (" + node.chapterCount() + " 章)");
                showOutline(node.children, indent + 1);
            } else if (indent <= 4) {
                out.println(pad + "  - " + node.title);
            }
        }
    }

    private static int countBlank(ArrayList<Outline> chapters) {
        int total = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).title.length() == 0) {
                total++;
            }
        }
        return total;
    }

    private static int countNonForum(ArrayList<Outline> chapters) {
        int total = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).url.indexOf("/forum/") < 0) {
                total++;
            }
        }
        return total;
    }

    private static String format(ArrayList<String[]> options) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            sb.append(options.get(i)[0]).append('=').append(options.get(i)[1]).append(' ');
        }
        return sb.toString().trim();
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private ParserHarness() {
    }
}
