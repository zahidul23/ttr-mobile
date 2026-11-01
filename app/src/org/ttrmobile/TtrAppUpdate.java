package org.ttrmobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * Release builds: a line on the launcher showing TTR Mobile's version and whether a newer release is on GitHub.
 * When there is one, tapping the line opens the release page, where the player downloads and installs it as they
 * did the first time (no install permission needed). GitHub is asked at most once an hour; the last answer is kept.
 * Test builds (dev-server.txt) get their updates from the PC and show no line.
 */
public final class TtrAppUpdate {
    private TtrAppUpdate() { }
    private static final String LATEST = "https://api.github.com/repos/zahidul23/ttr-mobile/releases/latest";
    private static final String PAGE = "https://github.com/zahidul23/ttr-mobile/releases/latest";
    private static final long INTERVAL = 60 * 60 * 1000L;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static WeakReference<TextView> line = new WeakReference<>(null);
    private static int muted, accent;
    private static volatile boolean checking;

    /** Called by the launcher screen (TtrLoginScreen) with its version line and colours. */
    public static void attach(TextView view, int mutedColour, int accentColour) {
        line = new WeakReference<>(view); muted = mutedColour; accent = accentColour;
        render();
    }

    /** Asks GitHub for the latest release when the last answer is over an hour old. */
    public static void start(Context a) {
        Context c = a.getApplicationContext();
        if (checking || testBuild(c)) return;
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("checked", 0) < INTERVAL) return;
        checking = true;
        Thread t = new Thread(() -> {
            try {
                String tag = new JSONObject(get(LATEST)).getString("tag_name");
                p.edit().putLong("checked", now).putString("latest", tag).apply();
            } catch (Exception e) {
                TtrDiagnostics.log(c, "Update check failed: " + e.getMessage());
            } finally { checking = false; ui.post(TtrAppUpdate::render); }
        }, "ttr-update-check");
        t.setDaemon(true); t.start();
    }

    private static void render() {
        TextView v = line.get(); if (v == null) return;
        Context c = v.getContext();
        if (testBuild(c)) { v.setVisibility(View.GONE); return; }
        String installed = installed(c), latest = prefs(c).getString("latest", "");
        v.setVisibility(View.VISIBLE);
        if (!latest.isEmpty() && isNewer(latest, installed)) {
            v.setText("TTR Mobile Update Available: v" + latest.replaceFirst("^v", "") + "  ·  Download");
            v.setTextColor(accent);
            v.setOnClickListener(x -> { try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))); } catch (Exception e) { TtrDiagnostics.log(c, "Could not open the release page: " + e); } });
        } else {
            v.setText("TTR Mobile v" + installed + (latest.isEmpty() ? "" : "  ·  Up to date"));
            v.setTextColor(muted);
            v.setOnClickListener(null); v.setClickable(false);
        }
    }

    /** True when tag (e.g. "v0.8.2") is a later version than installed (e.g. "0.8.1"). */
    static boolean isNewer(String tag, String installed) {
        int[] a = parse(tag), b = parse(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }
    private static int[] parse(String v) {
        String[] parts = v.replaceFirst("^v", "").split("[-+]", 2)[0].split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) { try { out[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException e) { out[i] = 0; } }
        return out;
    }

    private static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }
    private static boolean testBuild(Context c) {
        try (InputStream in = c.getAssets().open("dev-server.txt")) { return true; } catch (IOException e) { return false; }
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ttr-app-update", 0); }

    private static String get(String url) throws IOException {
        HttpsURLConnection h = (HttpsURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(15000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "TTR-Mobile (unofficial Android launcher)");
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("GitHub answered " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while (out.size() < 1_000_000 && (n = in.read(b)) > 0) out.write(b, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { h.disconnect(); }
    }
}
