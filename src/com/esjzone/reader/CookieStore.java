package com.esjzone.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

/**
 * Holds the session cookies captured from the login WebView.
 *
 * <p>The site authenticates with two cookies ({@code ews_key} / {@code ews_token}) and has no
 * token API, so the app logs the user in inside a WebView and copies the cookie jar out of it.
 * Everything else is then plain HTTP with that Cookie header.</p>
 */
public final class CookieStore {

    private static final String PREFS = "esjzone_session";
    private static final String KEY_COOKIE = "cookie";
    private static final String KEY_USER = "user";

    private final SharedPreferences prefs;

    public CookieStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The Cookie header value, or "" when the user has not signed in yet. */
    public String header() {
        return prefs.getString(KEY_COOKIE, "");
    }

    public boolean hasSession() {
        String cookie = header();
        return cookie.contains("ews_key") || cookie.contains("ews_token");
    }

    public void save(String cookie, String userName) {
        prefs.edit()
                .putString(KEY_COOKIE, cookie == null ? "" : cookie)
                .putString(KEY_USER, userName == null ? "" : userName)
                .apply();
    }

    public String userName() {
        return prefs.getString(KEY_USER, "");
    }

    public void clear() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_USER).apply();
    }

    /**
     * Reads the cookie jar the WebView is using. {@link CookieManager#getCookie} only returns
     * cookies for URLs already visited in that WebView, which is why login loads the site root
     * before the form.
     */
    public static String readFromWebView() {
        CookieManager manager = CookieManager.getInstance();
        StringBuilder sb = new StringBuilder();
        String[] urls = {Site.BASE + "/", Site.BASE + "/my/favorite"};
        for (int i = 0; i < urls.length; i++) {
            String cookie = manager.getCookie(urls[i]);
            if (cookie != null && cookie.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(cookie);
            }
        }
        return sb.toString();
    }
}
