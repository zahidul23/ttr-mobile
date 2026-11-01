package org.ttrmobile;

import android.app.Activity;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Status folder shared with the Windows bootstrap, plus a small bounded app log. */
public final class TtrDiagnostics {
    private TtrDiagnostics() { }
    /** Reachable from Wine as Z:\storage\...; no storage permission is needed for the app's own folder. */
    public static File windowsDirectory(Context c) throws IOException {
        File base = c.getExternalCacheDir(); if (base == null) base = c.getCacheDir();
        File dir = new File(base, "ttr-diagnostics");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create the diagnostics folder.");
        return dir;
    }
    public static synchronized void log(Context c, String message) {
        try {
            File file = new File(windowsDirectory(c), "ttr-mobile.log");
            if (file.length() > 1_000_000) file.delete();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + message + "\n");
            }
        } catch (IOException ignored) { }
    }
    private static boolean handlerInstalled;
    /** Java crashes are written to the app log before Android ends the process. */
    public static synchronized void install(Context c) {
        if (handlerInstalled) return; handlerInstalled = true;
        Context app = c.getApplicationContext(); Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            log(app, "Crash in " + thread.getName() + ": " + trace);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
    /** The last max bytes of a log, without lines that could hold login secrets. */
    static String filtered(File f, int max) { return tail(f, max); }
    private static String tail(File f, int max) {
        try {
            if (!f.isFile()) return "(none)\n";
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                long start = Math.max(0, r.length() - max); r.seek(start); byte[] b = new byte[(int) (r.length() - start)]; r.readFully(b);
                StringBuilder out = new StringBuilder();
                // Never include anything that could be a login secret.
                for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) {
                    String lower = line.toLowerCase(Locale.US);
                    if (lower.contains("cookie") || lower.contains("token") || lower.contains("password") || lower.contains("gameserver")) continue;
                    // Wine repeats this harmless notice for every program it starts.
                    if (lower.contains("freetype") || lower.contains("truetype fonts") || lower.contains("or equal to 2.0.5")) continue;
                    out.append(line).append('\n');
                }
                return out.toString();
            }
        } catch (IOException e) { return "(unreadable)\n"; }
    }
    private static String property(String name) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name); } catch (Exception e) { return ""; }
    }
    /** Plain-text report for sharing: device, recent app exits (including native crashes), and log tails. */
    public static String report(Context c) {
        StringBuilder s = new StringBuilder("TTR Mobile diagnostics\n");
        try { s.append("App ").append(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName).append('\n'); } catch (Exception ignored) { }
        s.append("Device ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" (").append(android.os.Build.DEVICE).append(")\n");
        s.append("Android ").append(android.os.Build.VERSION.RELEASE).append(" SDK ").append(android.os.Build.VERSION.SDK_INT)
            .append(", patch ").append(android.os.Build.VERSION.SECURITY_PATCH).append(", build ").append(android.os.Build.DISPLAY).append('\n');
        if (android.os.Build.VERSION.SDK_INT >= 31) s.append("SoC ").append(android.os.Build.SOC_MANUFACTURER).append(' ').append(android.os.Build.SOC_MODEL).append('\n');
        s.append("Board ").append(android.os.Build.BOARD).append(", hardware ").append(android.os.Build.HARDWARE).append(", kernel ").append(System.getProperty("os.version")).append('\n');
        s.append("Page size ").append(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).append(", ABIs ").append(Arrays.toString(android.os.Build.SUPPORTED_ABIS)).append('\n');
        s.append("GPU driver ").append(property("ro.hardware.egl")).append(' ').append(property("ro.hardware.vulkan")).append('\n');
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(memory);
        s.append("Memory ").append(memory.totalMem / 1048576).append(" MB total, ").append(memory.availMem / 1048576).append(" MB free\n");
        s.append("Windows screen ").append(TtrViewport.rootWidth).append('x').append(TtrViewport.renderHeight).append(", game ").append(TtrViewport.renderWidth).append('x').append(TtrViewport.renderHeight).append('\n');
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            s.append("\nRecent app exits:\n");
            for (android.app.ApplicationExitInfo e : am.getHistoricalProcessExitReasons(null, 0, 5)) {
                s.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(e.getTimestamp()))).append(" reason ").append(e.getReason())
                    .append(" status ").append(e.getStatus()).append(" importance ").append(e.getImportance()).append(" pss ").append(e.getPss() / 1024).append(" MB: ").append(e.getDescription()).append('\n');
                if (e.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || e.getReason() == android.app.ApplicationExitInfo.REASON_ANR) {
                    try (InputStream in = e.getTraceInputStream()) {
                        if (in != null) { byte[] b = new byte[12000]; int n, at = 0; while (at < b.length && (n = in.read(b, at, b.length - at)) > 0) at += n; s.append(new String(b, 0, at, StandardCharsets.UTF_8)).append('\n'); }
                    } catch (IOException ignored) { }
                }
            }
        }
        try { s.append("\nApp log:\n").append(tail(new File(windowsDirectory(c), "ttr-mobile.log"), 12000)); } catch (IOException ignored) { }
        try {
            File windows = windowsDirectory(c);
            s.append("\nWindows status: ").append(tail(new File(windows, "status.json"), 400));
            s.append("\nWindows helper log:\n").append(tail(new File(windows, "bootstrap.log"), 6000));
            s.append("\nPrevious Windows helper log:\n").append(tail(new File(windows, "bootstrap-previous.log"), 6000));
        } catch (IOException ignored) { }
        s.append("\nRunning Windows programs:\n").append(programs());
        File[] logs = new File(c.getFilesDir(), "ttr/game/logs").listFiles((d, n) -> n.endsWith(".log"));
        if (logs != null && logs.length > 0) {
            File newest = logs[0]; for (File f : logs) if (f.lastModified() > newest.lastModified()) newest = f;
            s.append("\nGame log (").append(newest.getName()).append("):\n").append(tail(newest, 8000));
        }
        s.append("\nDetailed logging: ").append(TtrRuntime.detailedLogging(c) ? "on" : "off").append('\n');
        s.append("\nWine log:\n").append(tail(new File(c.getCacheDir(), "wine.log"), TtrRuntime.detailedLogging(c) ? 60000 : 16000));
        return s.toString();
    }
    /** Program names of this app's running processes (names only: no paths or arguments). */
    private static String programs() {
        StringBuilder out = new StringBuilder(); File[] all = new File("/proc").listFiles();
        if (all != null) for (File p : all) {
            if (!p.getName().matches("[0-9]+")) continue;
            try {
                String[] args = new String(java.nio.file.Files.readAllBytes(new File(p, "cmdline").toPath()), StandardCharsets.UTF_8).split("\u0000");
                if (args.length == 0 || args[0].isEmpty()) continue;
                String program = args[0].endsWith("linker64") && args.length > 1 ? args[1] : args[0];
                out.append(p.getName()).append(' ').append(program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1));
                // parent, process group, session and CPU seconds, to see how Windows' processes are grouped
                String stat = new String(java.nio.file.Files.readAllBytes(new File(p, "stat").toPath()), StandardCharsets.UTF_8);
                String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
                out.append(" state ").append(f[0]).append(" parent ").append(f[1]).append(" group ").append(f[2]).append(" session ").append(f[3])
                    .append(" cpu ").append(ticks / 100).append(" s").append('\n');
            } catch (IOException | RuntimeException ignored) { }
        }
        return out.length() == 0 ? "(none)\n" : out.toString();
    }
    public static void share(Activity a) {
        new Thread(() -> {
            String text = report(a);
            a.runOnUiThread(() -> {
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "TTR Mobile diagnostics").putExtra(android.content.Intent.EXTRA_TEXT, text);
                a.startActivity(android.content.Intent.createChooser(send, "Share diagnostics"));
            });
        }, "ttr-diagnostics").start();
    }
    public static void phase(Context c, String phase) { log(c, phase); }
    public static void beginAttempt(Context c) { }
    public static void show(Activity a) {
        String text;
        try { File f = new File(windowsDirectory(a), "ttr-mobile.log"); text = f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "No diagnostics yet."; }
        catch (IOException e) { text = "Diagnostics unavailable."; }
        if (text.length() > 6000) text = text.substring(text.length() - 6000);
        new android.app.AlertDialog.Builder(a).setTitle("Diagnostics").setMessage(text).setPositiveButton("OK", null).show();
    }
}
