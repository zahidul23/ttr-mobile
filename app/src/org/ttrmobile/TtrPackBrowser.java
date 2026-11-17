package org.ttrmobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * A small web browser for finding content packs. A download it is asked for is saved straight into the content packs
 * folder (TtrPackDownload). It only opens web pages: no file access, no scripts reaching into the app.
 */
public final class TtrPackBrowser extends Activity {
    private static final String START = "https://www.google.com/search?q=toontown+rewritten+content+packs";
    private static final int BAR = 0xff102b3a, FIELD = 0xff173344, SOFT = 0xffdcebf1, GOLD = 0xffffd65e;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web; private EditText address; private ProgressBar progress; private TextView status; private Button back;

    public static void open(Activity from) { from.startActivity(new Intent(from, TtrPackBrowser.class)); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private Button flat(String label, String description, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(18); b.setContentDescription(description);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(10), 0, dp(10), 0); b.setBackgroundColor(0); b.setStateListAnimator(null); b.setOnClickListener(v -> action.run()); return b;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BAR);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(4), dp(6), dp(8), dp(6));
        Button close = flat("✕", "Close browser", this::finish); bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        back = flat("◀", "Back", () -> { if (web.canGoBack()) web.goBack(); }); bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(14); address.setTextColor(Color.WHITE); address.setHintTextColor(0xffb4c9d4); address.setHint("Search or type a web address");
        address.setSelectAllOnFocus(true); address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable pill = new GradientDrawable(); pill.setColor(FIELD); pill.setCornerRadius(dp(18)); address.setBackground(pill); address.setPadding(dp(14), 0, dp(14), 0);
        address.setOnEditorActionListener((v, action, e) -> { go(address.getText().toString()); return true; });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(40), 1); ap.leftMargin = dp(4); bar.addView(address, ap);
        root.addView(bar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD)); progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33ffffff));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        web = new WebView(this); root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this); status.setTextColor(SOFT); status.setTextSize(13); status.setPadding(dp(14), dp(8), dp(14), dp(8)); status.setBackgroundColor(BAR);
        status.setText("Content packs you download are saved to your game. Keep this screen open until a download finishes."); root.addView(status);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()), ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, Math.max(b.bottom, ime.bottom));
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSupportMultipleWindows(false); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("https".equals(scheme)) return false;
                say("Only secure (https) pages open here."); return true;
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) { if (!address.hasFocus()) address.setText(url); }
            @Override public void onPageFinished(WebView v, String url) { back.setAlpha(v.canGoBack() ? 1f : .35f); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE); }
        });
        web.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (!url.startsWith("https://")) { say("That download can't be saved here. Look for a direct download link."); return; }
            say("Checking the download\u2026");
            new Thread(() -> {
                try {
                    TtrPackDownload.Info info = TtrPackDownload.probe(url, agent, disposition, mime);
                    if (info.size < 0 && length > 0) info.size = length;
                    ui.post(() -> confirm(url, agent, disposition, mime, info));
                } catch (Exception e) { ui.post(() -> say(e.getMessage() == null ? "The download failed." : e.getMessage())); }
            }, "ttr-pack-probe").start();
        });
        back.setAlpha(.35f);
        web.loadUrl(START);
    }
    /** Shows the file's name and size and asks before downloading it. */
    private void confirm(String url, String agent, String disposition, String mime, TtrPackDownload.Info info) {
        if (isFinishing() || isDestroyed()) return;
        if (info.size > TtrPackDownload.LIMIT) { say("That file is too large (" + size(info.size) + ")."); return; }
        say("Waiting for your OK to download " + info.name + ".");
        new TtrDialog(this, "Download this file?", info.name + "\n" + (info.size > 0 ? "Size: " + size(info.size) : "Size: not given by the site"))
            .button("Download", TtrDialog.PRIMARY, () -> {
                if (TtrPackDownload.start(this, url, agent, disposition, mime, (message, finished) -> ui.post(() -> say(message)))) say("Starting download\u2026");
                else say("A download is already running.");
            })
            .button("Cancel", TtrDialog.QUIET, () -> say("Download cancelled.")).onCancel(() -> say("Download cancelled.")).show();
    }
    static String size(long bytes) { return TtrPacks.readable(bytes); }
    private void say(String message) { status.setText(message); }
    private void go(String input) {
        input = input.trim(); if (input.isEmpty()) return;
        String url = input.matches("(?i)https?://\\S+") ? input : input.matches("\\S+\\.[a-zA-Z]{2,}(/\\S*)?") ? "https://" + input : "https://www.google.com/search?q=" + Uri.encode(input);
        address.clearFocus(); InputMethodManager m = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (m != null) m.hideSoftInputFromWindow(address.getWindowToken(), 0);
        web.loadUrl(url);
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else finish(); }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); if (web != null) { web.stopLoading(); web.destroy(); } super.onDestroy(); }
}
