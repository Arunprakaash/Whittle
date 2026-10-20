package app.whittle.shell;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Runs the app packaged in assets/www/ on a private https origin, like Whittle's own viewer: JavaScript and its
 * own storage, no file access, links to other sites open in the browser.
 */
public final class AppActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ROOT = "www/";
    private static final String ENTRY = "index.html";
    /** The page's own colour for the bars: its theme-color, else its background. */
    private static final String PAGE_COLOR = "(function(){var m=document.querySelector('meta[name=\"theme-color\"]');"
            + "return m&&m.content?m.content:getComputedStyle(document.body).backgroundColor;})()";
    /** Above this brightness (0–1) the page is light, so the bar icons turn dark. */
    private static final double LIGHT_PAGE = 0.6;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new AssetClient());
        keepClearOfSystemBars(web);
        setContentView(web);
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl("https://" + HOST + "/" + ENTRY);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Android 15+ draws apps edge to edge; the page keeps clear of the status and navigation bars. */
    private static void keepClearOfSystemBars(WebView view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            view.setFitsSystemWindows(true);
            return;
        }
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    /** Dark status and navigation icons over light pages, light ones over dark pages. */
    @SuppressWarnings("deprecation")
    private void matchBarsTo(String cssColor) {
        Integer color = parseColor(cssColor);
        if (color == null) return;
        boolean light = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255 > LIGHT_PAGE;
        getWindow().getDecorView().setBackgroundColor(color);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light ? bars : 0, bars);
        } else {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(light ? decor.getSystemUiVisibility() | flags : decor.getSystemUiVisibility() & ~flags);
        }
    }

    /** A CSS colour as evaluateJavascript returns it: a JSON string like "rgb(1, 2, 3)" or "#rrggbb". */
    private static Integer parseColor(String json) {
        if (json == null) return null;
        String css = json.replace("\"", "").trim().toLowerCase(Locale.ROOT);
        try {
            if (css.startsWith("#")) return Color.parseColor(css);
            if (!css.startsWith("rgb")) return null;
            String[] parts = css.substring(css.indexOf('(') + 1, css.indexOf(')')).split("[,\\s/]+");
            if (parts.length > 3 && Double.parseDouble(parts[3]) == 0) return null;
            return Color.rgb(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(PAGE_COLOR, AppActivity.this::matchBarsTo);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost())) return null;
            String path = url.getPath() == null ? "" : url.getPath().replaceFirst("^/+", "");
            if (path.isEmpty()) path = ENTRY;
            try {
                InputStream data = getAssets().open(ROOT + path);
                return new WebResourceResponse(mimeType(path), null, data);
            } catch (IOException missing) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (HOST.equals(request.getUrl().getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
            } catch (ActivityNotFoundException ignored) {
                // Nothing on the phone opens this link.
            }
            return true;
        }
    }

    private static String mimeType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": return "application/json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "txt": return "text/plain";
            case "wasm": return "application/wasm";
            default: return "application/octet-stream";
        }
    }
}
