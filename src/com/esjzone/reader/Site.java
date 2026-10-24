package com.esjzone.reader;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Site constants and URL normalisation.
 *
 * <p>The mirror-era scripts point at esjzone.cc / esjzone.me, which no longer answer; only
 * esjzone.one does. Pages still mix the old hosts into their links, so every URL that comes out
 * of a parser is passed through {@link #canonical(String)}.</p>
 */
public final class Site {

    public static final String HOST = "www.esjzone.one";
    public static final String BASE = "https://" + HOST;

    private static final String[] STALE_PREFIXES = {
            "https://www.esjzone.cc", "http://www.esjzone.cc",
            "https://esjzone.cc", "http://esjzone.cc",
            "https://www.esjzone.me", "http://www.esjzone.me",
            "https://esjzone.me", "http://esjzone.me",
    };

    private static final Pattern BOOK_ID = Pattern.compile("/detail/(\\d+)");

    /** Rewrites a dead domain onto the live one and resolves relative links. */
    public static String canonical(String url) {
        return canonical(BASE + "/", url);
    }

    public static String canonical(String baseUri, String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        for (String stale : STALE_PREFIXES) {
            if (trimmed.startsWith(stale)) {
                return BASE + trimmed.substring(stale.length());
            }
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return BASE + trimmed;
        }
        if (baseUri == null || baseUri.length() == 0) {
            return BASE + "/" + trimmed;
        }
        try {
            return URI.create(baseUri).resolve(trimmed).toString();
        } catch (IllegalArgumentException malformed) {
            return BASE + "/" + trimmed;
        }
    }

    public static String favorites(int page) {
        return page <= 1 ? BASE + "/my/favorite" : BASE + "/my/favorite/" + page;
    }

    public static String favorites(String sort, int page) {
        if (sort == null || sort.length() == 0) {
            return favorites(page);
        }
        return page <= 1 ? BASE + "/my/favorite/" + sort
                : BASE + "/my/favorite/" + sort + "/" + page;
    }

    public static String detail(String bookId) {
        return BASE + "/detail/" + bookId + ".html";
    }

    public static String login() {
        return BASE + "/my/login";
    }

    /** Extracts the numeric book id from a detail URL, or "" when there is none. */
    public static String bookId(String detailUrl) {
        Matcher matcher = BOOK_ID.matcher(detailUrl == null ? "" : detailUrl);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Site() {
    }
}
