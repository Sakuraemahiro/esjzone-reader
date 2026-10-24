package com.esjzone.reader;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

/**
 * Signs the user in with a real WebView, then copies the session cookies out of it.
 *
 * <p>The site has no token API, and its login form is a plain POST, so the reliable route is to
 * let the user sign in normally and read the cookie jar afterwards. Success is detected by looking
 * for the member menu the site only renders to a signed-in visitor.</p>
 */
public class LoginActivity extends Activity {

    /** Evaluates to "1" only when the page shows the signed-in member menu. */
    private static final String LOGGED_IN_PROBE =
            "(function(){try{return document.querySelector('a[href*=\"/my/logout\"]')?'1':'0';}"
                    + "catch(e){return '0';}})()";

    private WebView web;
    private ProgressBar progress;
    private CookieStore cookies;
    private boolean settled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        cookies = new CookieStore(this);
        web = (WebView) findViewById(R.id.webview);
        progress = (ProgressBar) findViewById(R.id.login_progress);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (settled) {
                    return;
                }
                // the login page itself never counts as success
                if (url != null && url.contains("/my/login")) {
                    return;
                }
                web.evaluateJavascript(LOGGED_IN_PROBE, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!settled && value != null && value.indexOf('1') >= 0) {
                            complete();
                        }
                    }
                });
            }
        });

        ((Button) findViewById(R.id.login_done)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyManually();
            }
        });
        ((Button) findViewById(R.id.login_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        web.loadUrl(Site.login());
    }

    /** The explicit button: verify the same way, and say so when the user is not signed in yet. */
    private void verifyManually() {
        web.evaluateJavascript(LOGGED_IN_PROBE, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value != null && value.indexOf('1') >= 0) {
                    complete();
                } else {
                    Toast.makeText(LoginActivity.this, "还没有检测到登录状态，请先完成登录",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void complete() {
        if (settled) {
            return;
        }
        settled = true;
        cookies.save(CookieStore.readFromWebView(), "");
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
