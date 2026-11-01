package org.ttrmobile;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/**
 * Test builds only (tools/dev_server.py): the build bakes the PC's address into assets/dev-server.txt.
 * Every few seconds the app sends its diagnostics and logs to the PC, picks up developer switch files,
 * and offers to install a newer APK the PC is serving. Without that asset this class does nothing.
 */
public final class TtrDevServer {
    private TtrDevServer() { }
    private static final String INSTALL_ACTION = "org.ttrmobile.DEV_INSTALL_STATUS";
    private static String server;
    private static boolean started;
    // Offers: never two dialogs at once, at most every 30 s, not again after "Not now", and again after an
    // install that was cancelled, failed or never reported back (e.g. the user went to allow installs first).
    private static volatile boolean dialogOpen;
    private static volatile long lastOffer, installStarted;
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static final Set<String> declined = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, Long> sent = new HashMap<>();
    private static String ownHash;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public static void start(Activity a) {
        current = new WeakReference<>(a);
        if (started) return; started = true;
        try (InputStream in = a.getAssets().open("dev-server.txt")) {
            server = new String(read(in, 4096), StandardCharsets.UTF_8).trim().replaceAll("/+$", "");
        } catch (IOException e) { return; }
        Context app = a.getApplicationContext();
        TtrDiagnostics.log(app, "Test build: sending logs to " + server);
        Thread t = new Thread(() -> loop(app), "ttr-dev-server"); t.setDaemon(true); t.start();
    }
    public static void stop(Activity a) { if (current.get() == a) current.clear(); }

    private static void loop(Context c) {
        int failures = 0;
        while (true) {
            try {
                JSONObject state = new JSONObject(new String(request("GET", "/api/state?device=" + enc(device()), null), StandardCharsets.UTF_8));
                applyConfig(c, state.optJSONObject("config"), state.optInt("config_rev", 0));
                upload(c);
                String apk = state.optString("apk_sha256", "");
                long now = System.currentTimeMillis();
                if (!apk.isEmpty() && !apk.equals(ownHash(c)) && !declined.contains(apk) && !dialogOpen && now - lastOffer > 30000 && now - installStarted > 120000) ui.post(() -> offer(apk, state.optString("apk_name", "")));
                failures = 0;
            } catch (Exception e) {
                if (failures++ == 0) TtrDiagnostics.log(c, "Test build: PC not reachable (" + e + ")");
            }
            try { Thread.sleep(failures > 3 ? 15000 : 4000); } catch (InterruptedException e) { return; }
        }
    }

    // Logs: the full report every round, the raw log files when they change. The same filter as
    // "Share diagnostics" removes lines that could hold login secrets.
    private static void upload(Context c) throws Exception {
        send("report.txt", (TtrDiagnostics.report(c)).getBytes(StandardCharsets.UTF_8));
        File windows = TtrDiagnostics.windowsDirectory(c);
        List<File> files = new ArrayList<>(Arrays.asList(new File(c.getCacheDir(), "wine.log"), new File(windows, "ttr-mobile.log"),
            new File(windows, "bootstrap.log"), new File(windows, "bootstrap-previous.log"), new File(windows, "status.json")));
        File[] game = new File(c.getFilesDir(), "ttr/game/logs").listFiles((d, n) -> n.endsWith(".log"));
        if (game != null && game.length > 0) { Arrays.sort(game, (x, y) -> Long.compare(y.lastModified(), x.lastModified())); files.add(game[0]); }
        for (File f : files) {
            if (!f.isFile()) continue;
            long stamp = f.lastModified() * 31 + f.length();
            Long last = sent.get(f.getName()); if (last != null && last == stamp) continue;
            send(f.getName(), TtrDiagnostics.filtered(f, 4_000_000).getBytes(StandardCharsets.UTF_8));
            sent.put(f.getName(), stamp);
        }
    }
    private static void send(String name, byte[] body) throws IOException {
        request("POST", "/api/log?device=" + enc(device()) + "&name=" + enc(name), body);
    }

    // Developer switch files in no_backup/ttr (winedebug, gl-env, present-off, ...); a null value removes one.
    // They take effect on the next game launch.
    private static void applyConfig(Context c, JSONObject files, int rev) throws Exception {
        SharedPreferences p = c.getSharedPreferences("ttr-dev-server", 0);
        if (files == null || rev == p.getInt("config_rev", 0)) return;
        File dir = TtrRuntime.directory(c);
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            String name = it.next(); if (!name.matches("[a-z0-9][a-z0-9.-]*")) continue;
            File f = new File(dir, name);
            if (files.isNull(name)) { if (f.delete()) TtrDiagnostics.log(c, "Test build: removed switch " + name); }
            else { try (FileOutputStream out = new FileOutputStream(f)) { out.write(files.getString(name).getBytes(StandardCharsets.UTF_8)); }
                TtrDiagnostics.log(c, "Test build: set switch " + name); }
        }
        p.edit().putInt("config_rev", rev).apply();
    }

    private static void offer(String hash, String name) {
        Activity a = current.get(); if (a == null || a.isFinishing() || dialogOpen) return;
        dialogOpen = true; lastOffer = System.currentTimeMillis();
        new android.app.AlertDialog.Builder(a).setTitle("New test build on PC").setMessage(name + "\n\nInstall it now? TTR Mobile will close.")
            .setPositiveButton("Install", (d, w) -> download(a.getApplicationContext(), hash)).setNegativeButton("Not now", (d, w) -> declined.add(hash))
            .setOnDismissListener(d -> { dialogOpen = false; lastOffer = System.currentTimeMillis(); }).show();
    }
    private static void download(Context c, String hash) {
        installStarted = System.currentTimeMillis(); toast(c, "Downloading test build…");
        new Thread(() -> {
            try {
                File apk = new File(c.getCacheDir(), "dev-server.apk");
                try (FileOutputStream out = new FileOutputStream(apk)) { out.write(request("GET", "/apk", null)); }
                if (!hash.equals(sha256(apk))) throw new IOException("download did not match the PC's build");
                install(c, apk);
            } catch (Exception e) { installStarted = 0; toast(c, "Test build install failed: " + e.getMessage()); TtrDiagnostics.log(c, "Test build install failed: " + e); }
        }, "ttr-dev-install").start();
    }
    private static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); s.fsync(out);
            }
            BroadcastReceiver status = new BroadcastReceiver() { @Override public void onReceive(Context x, Intent i) {
                int code = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
                installStarted = 0; c.unregisterReceiver(this);
                String message = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                TtrDiagnostics.log(c, "Test build install status " + code + ": " + message);
                if (code != PackageInstaller.STATUS_SUCCESS) toast(c, "Test build not installed: " + message);
            }};
            IntentFilter filter = new IntentFilter(INSTALL_ACTION);
            if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(status, filter, Context.RECEIVER_NOT_EXPORTED); else c.registerReceiver(status, filter);
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(INSTALL_ACTION).setPackage(c.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            s.commit(done.getIntentSender());
        }
    }

    private static byte[] request(String method, String path, byte[] body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(server + path).openConnection();
        h.setConnectTimeout(3000); h.setReadTimeout(60000); h.setRequestMethod(method);
        if (body != null) { h.setDoOutput(true); h.setFixedLengthStreamingMode(body.length); h.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream out = h.getOutputStream()) { out.write(body); } }
        int code = h.getResponseCode(); if (code != 200) { h.disconnect(); throw new IOException("HTTP " + code + " for " + path); }
        try (InputStream in = h.getInputStream()) { return read(in, Integer.MAX_VALUE); } finally { h.disconnect(); }
    }
    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1 << 16]; int n;
        while (out.size() < max && (n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }
    private static synchronized String ownHash(Context c) {
        if (ownHash == null) try { ownHash = sha256(new File(c.getPackageCodePath())); } catch (Exception e) { ownHash = "?"; }
        return ownHash;
    }
    private static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte x : d.digest()) s.append(String.format("%02x", x)); return s.toString();
    }
    private static String device() { return (android.os.Build.MANUFACTURER + "-" + android.os.Build.MODEL).replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; } }
    private static void toast(Context c, String text) { ui.post(() -> Toast.makeText(c, text, Toast.LENGTH_LONG).show()); }
}
