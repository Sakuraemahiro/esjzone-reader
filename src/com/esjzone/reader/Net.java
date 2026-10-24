package com.esjzone.reader;

import android.os.Build;

import java.net.HttpURLConnection;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS compatibility for the older 32-bit releases this app still supports.
 *
 * <p>The mirror is served over HTTPS with TLS 1.2 or better. Android releases before 7.0 keep
 * TLS 1.2 switched off for {@link HttpURLConnection} unless an explicit socket factory asks for
 * it, so without this the download would fail on Android 5 and 6.</p>
 */
final class Net {

    private static volatile boolean prepared;

    static void prepareIfNeeded() {
        if (prepared) {
            return;
        }
        synchronized (Net.class) {
            if (prepared) {
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                try {
                    SSLContext context = SSLContext.getInstance("TLSv1.2");
                    context.init(null, null, new SecureRandom());
                    SSLSocketFactory factory = context.getSocketFactory();
                    HttpsURLConnection.setDefaultSSLSocketFactory(factory);
                } catch (Exception ignored) {
                    // leave the platform default in place; the request will report the failure
                }
            }
            prepared = true;
        }
    }

    private Net() {
    }
}
