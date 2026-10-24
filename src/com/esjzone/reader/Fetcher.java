package com.esjzone.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Plain HTTP GET with the session cookie, retries, and charset handling.
 *
 * <p>The site answers an unauthenticated request for a book or chapter with HTTP 200 whose body
 * is the forum index, so callers must test the parsed result rather than the status code.
 * {@link #looksSignedOut(String)} covers that check.</p>
 */
public final class Fetcher {

    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern CHARSET =
            Pattern.compile("charset=[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset=[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int DEFAULT_CONNECT_TIMEOUT = 12000;
    private static final int DEFAULT_READ_TIMEOUT = 60000;
    private static final String ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    /** One response body plus the charset and media type the server declared for it. */
    public static final class Response {
        public final byte[] body;
        public final String charset;
        public final String contentType;

        Response(byte[] body, String charset, String contentType) {
            this.body = body;
            this.charset = charset;
            this.contentType = contentType == null ? "" : contentType;
        }

        public String text() {
            try {
                return new String(body, Charset.forName(charset));
            } catch (Exception unsupported) {
                return new String(body, Charset.forName("UTF-8"));
            }
        }

        public long length() {
            return body.length;
        }
    }

    private final CookieStore cookies;

    public Fetcher(CookieStore cookies) {
        this.cookies = cookies;
        Net.prepareIfNeeded();
    }

    public Response fetch(String url) throws IOException {
        return fetch(url, MAX_ATTEMPTS, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * @param attempts        how many tries before giving up; images use 1 so a dead host is
     *                        detected quickly instead of stalling the whole book
     * @param connectTimeout  milliseconds; kept short for the same reason
     */
    public Response fetch(String url, int attempts, int connectTimeout, int readTimeout)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return once(url, connectTimeout, readTimeout);
            } catch (IOException error) {
                last = error;
                if (attempt < attempts) {
                    try {
                        Thread.sleep(1200L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("已取消");
                    }
                }
            }
        }
        throw last != null ? last : new IOException("请求失败");
    }

    /** Convenience for HTML pages. */
    public String html(String url) throws IOException {
        return fetch(url).text();
    }

    public byte[] bytes(String url) throws IOException {
        return fetch(url).body;
    }

    private Response once(String url, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(Site.canonical(url)).openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", ACCEPT_HTML);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,zh-TW;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            String cookie = cookies.header();
            if (cookie.length() > 0) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("服务器返回 HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            byte[] body;
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 18);
                byte[] chunk = new byte[64 * 1024];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                body = buffer.toByteArray();
            } finally {
                in.close();
            }
            return new Response(body, resolveCharset(conn.getContentType(), body),
                    conn.getContentType());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** HTTP header first, then any in-document declaration, then UTF-8. */
    static String resolveCharset(String contentType, byte[] body) {
        String fromHeader = match(CHARSET, contentType);
        if (fromHeader != null) {
            return normalise(fromHeader);
        }
        if (body != null && body.length > 0) {
            int sample = Math.min(body.length, 4096);
            String head = new String(body, 0, sample, Charset.forName("ISO-8859-1"));
            String fromMeta = match(META_CHARSET, head);
            if (fromMeta != null) {
                return normalise(fromMeta);
            }
        }
        return "UTF-8";
    }

    private static String match(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String normalise(String name) {
        if ("gb2312".equalsIgnoreCase(name) || "gbk".equalsIgnoreCase(name)) {
            return "GB18030";
        }
        return name;
    }

    /**
     * The site serves the forum index to anonymous visitors instead of a 403, so the only reliable
     * signal is that none of the expected structures are present.
     */
    public static boolean looksSignedOut(String html) {
        if (html == null) {
            return true;
        }
        return html.indexOf("id=\"chapterList\"") < 0
                && html.indexOf("forum-content") < 0
                && html.indexOf("product-item") < 0
                && html.indexOf("會員專區") < 0
                && html.indexOf("会员专区") < 0;
    }

    public static String describe(Exception error) {
        if (error instanceof UnknownHostException) {
            return "无法连接网络，请检查网络或代理";
        }
        if (error instanceof SocketTimeoutException) {
            return "网络超时，请稍后重试";
        }
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }
}
