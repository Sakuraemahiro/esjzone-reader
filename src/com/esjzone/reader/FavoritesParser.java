package com.esjzone.reader;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the /my/favorite list page.
 *
 * <p>The favourites page shares nothing with the public list pages: cards are
 * {@code div.product-item} rather than {@code div.col-lg-3}, there are 18 per page, and paging
 * goes to {@code /my/favorite/2} with no {@code .html} suffix. Chapter and update info live in
 * {@code .book-ep} / {@code .book-update}. There is no cover thumbnail.</p>
 */
public final class FavoritesParser {

    private static final Pattern BOOK_ID = Pattern.compile("/detail/(\\d+)");
    private static final Pattern PAGE_NUMBER = Pattern.compile("/my/favorite/(\\d+)(?![\\d/])");
    private static final Pattern BOOTPAG_TOTAL = Pattern.compile("total:\\s*(\\d+)");
    private static final Pattern SORT_OPTION =
            Pattern.compile("<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>");

    private static final String LAST_VIEWED_1 = "最後觀看";
    private static final String LAST_VIEWED_2 = "最后观看";
    private static final String UPDATE_LABEL_1 = "更新日期：";
    private static final String UPDATE_LABEL_2 = "更新日期:";

    public static ArrayList<Book> parse(Document doc, String baseUri) {
        ArrayList<Book> books = new ArrayList<Book>();
        Elements cards = doc.select("div.product-item");
        for (int i = 0; i < cards.size(); i++) {
            Element card = cards.get(i);
            Element link = card.selectFirst(".product-title a[href]");
            if (link == null) {
                link = card.selectFirst("a[href*=/detail/]");
            }
            if (link == null) {
                continue;
            }
            String detailUrl = Site.canonical(baseUri, link.absUrl("href"));
            Matcher id = BOOK_ID.matcher(detailUrl);
            if (!id.find()) {
                continue;
            }

            String latestTitle = "";
            String latestUrl = "";
            Element latest = card.selectFirst(".book-ep a[href]");
            if (latest != null) {
                latestTitle = text(latest);
                latestUrl = Site.canonical(baseUri, latest.absUrl("href"));
            }

            String lastViewed = "";
            Elements blocks = card.select(".book-ep > div");
            for (int b = 0; b < blocks.size(); b++) {
                String line = text(blocks.get(b));
                if (line.startsWith(LAST_VIEWED_1) || line.startsWith(LAST_VIEWED_2)) {
                    int colon = Math.max(line.indexOf('：'), line.indexOf(':'));
                    lastViewed = colon >= 0 ? line.substring(colon + 1).trim() : line;
                }
            }

            String updated = "";
            Element update = card.selectFirst(".book-update");
            if (update != null) {
                updated = text(update).replace(UPDATE_LABEL_1, "").replace(UPDATE_LABEL_2, "").trim();
            }

            books.add(new Book(id.group(1), text(link), detailUrl, latestTitle, latestUrl,
                    lastViewed, updated));
        }
        return books;
    }

    /**
     * Number of pages of favourites.
     *
     * <p>bootpag renders real anchors, so the page numbers can be counted without running any
     * JavaScript. 1 is returned when the member has a single page.</p>
     */
    public static int pageCount(String html) {
        int highest = 0;
        Matcher numbers = PAGE_NUMBER.matcher(html);
        while (numbers.find()) {
            try {
                highest = Math.max(highest, Integer.parseInt(numbers.group(1)));
            } catch (NumberFormatException ignored) {
                // a non-numeric match cannot happen with this pattern
            }
        }
        if (highest > 0) {
            return highest;
        }
        Matcher total = BOOTPAG_TOTAL.matcher(html);
        if (total.find()) {
            try {
                return Math.max(1, Integer.parseInt(total.group(1)));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    /** The sort dropdown: "new" = 最新收藏, "udate" = 最新更新. */
    public static ArrayList<String[]> sortingOptions(String html) {
        ArrayList<String[]> options = new ArrayList<String[]>();
        Matcher matcher = SORT_OPTION.matcher(html);
        while (matcher.find()) {
            options.add(new String[]{matcher.group(1), matcher.group(2).trim()});
        }
        return options;
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }

    private FavoritesParser() {
    }
}
