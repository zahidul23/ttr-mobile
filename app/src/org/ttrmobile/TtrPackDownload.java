package org.ttrmobile;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Saves a file the in-app browser was asked to download into the content packs folder. The file must turn out to be a
 * pack (.mf), or a .zip holding packs; anything else is removed again. One download runs at a time.
 */
final class TtrPackDownload {
    private TtrPackDownload() { }
    interface Listener { void changed(String message, boolean finished); }
    static final long LIMIT = 1L << 30;
    private static boolean running;

    static synchronized boolean start(Context context, String url, String agent, String disposition, String mime, Listener listener) {
        if (running) return false;
        running = true; Context c = context.getApplicationContext();
        new Thread(() -> {
            String result; File part = new File(TtrPacks.folder(c), ".download.part");
            try { result = run(c, url, agent, disposition, mime, part, listener); }
            catch (Exception e) { result = e.getMessage() == null || e.getMessage().isEmpty() ? "The download failed." : e.getMessage(); }
            finally { part.delete(); synchronized (TtrPackDownload.class) { running = false; } }
            listener.changed(result, true);
        }, "ttr-pack-download").start();
        return true;
    }

    /** What the server says about a download before it starts. */
    static final class Info { String name; long size = -1; }

    private static final class Source { HttpURLConnection connection; String url; }
    /** Opens the file, following redirects (https only) and carrying the browser's cookies. */
    private static Source connect(String url, String agent) throws Exception {
        Source source = new Source(); String current = url;
        for (int hop = 0; ; hop++) {
            if (hop > 6) throw new IOException("Too many redirects.");
            URL u = new URL(current);
            if (!"https".equals(u.getProtocol())) throw new IOException("Only secure (https) downloads are allowed.");
            HttpURLConnection connection = (HttpURLConnection) u.openConnection(); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            if (agent != null) connection.setRequestProperty("User-Agent", agent);
            String cookies = CookieManager.getInstance().getCookie(current); if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400 && connection.getHeaderField("Location") != null) { current = new URL(u, connection.getHeaderField("Location")).toString(); connection.disconnect(); continue; }
            if (code != 200) { connection.disconnect(); throw new IOException("The site refused the download (" + code + ")."); }
            source.connection = connection; source.url = current; return source;
        }
    }
    private static String nameOf(Source source, String disposition, String mime) {
        String type = source.connection.getContentType(), disp = source.connection.getHeaderField("Content-Disposition");
        return clean(URLUtil.guessFileName(source.url, disp != null ? disp : disposition, type != null ? type : mime));
    }
    /** Asks the server for the file's name and size without downloading it (call off the main thread). */
    static Info probe(String url, String agent, String disposition, String mime) throws Exception {
        Source source = connect(url, agent);
        try { Info info = new Info(); info.name = nameOf(source, disposition, mime); info.size = source.connection.getContentLengthLong(); return info; }
        finally { source.connection.disconnect(); }
    }

    private static String run(Context c, String url, String agent, String disposition, String mime, File part, Listener listener) throws Exception {
        File folder = TtrPacks.folder(c);
        Source source = connect(url, agent); HttpURLConnection connection = source.connection;
        try {
            String name = nameOf(source, disposition, mime);
            long total = connection.getContentLengthLong();
            if (total > LIMIT) throw new IOException("That file is too large.");
            if (total > 0 && folder.getUsableSpace() < total + (64L << 20)) throw new IOException("Not enough free space on the phone.");
            long done = 0, shown = 0; byte[] buffer = new byte[65536];
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n); done += n;
                    if (done > LIMIT) throw new IOException("That file is too large.");
                    if (done - shown > 262144 || shown == 0) { shown = done; listener.changed("Downloading " + name + (total > 0 ? " (" + done * 100 / total + "%)" : " (" + done / 1048576 + " MB)"), false); }
                }
            }
            String added = install(folder, part, name); TtrPacks.syncNow(c); return added;
        } finally { connection.disconnect(); }
    }

    /** A safe file name: no folders, no characters Windows (the game runs under Wine) rejects, never hidden. */
    static String clean(String name) {
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim().replaceAll("^\\.+", "");
        if (name.length() > 100) name = name.substring(name.length() - 100);
        return name.isEmpty() ? "content-pack.mf" : name;
    }

    private static String install(File folder, File part, String name) throws IOException {
        if (TtrPacks.looksLikePack(part)) { place(folder, part, name.replaceFirst("(?i)\\.[a-z0-9]{1,5}$", "") + ".mf"); return "Added " + stem(name) + "."; }
        List<String> added = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(part)))) {
            ZipEntry entry; File extracted = new File(folder, ".extract.part");
            try {
                while ((entry = zip.getNextEntry()) != null) {
                    String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    if (entry.isDirectory() || !base.toLowerCase(java.util.Locale.ROOT).endsWith(".mf") || base.startsWith(".")) continue;
                    long size = 0; byte[] buffer = new byte[65536];
                    try (FileOutputStream out = new FileOutputStream(extracted)) { int n; while ((n = zip.read(buffer)) > 0) { out.write(buffer, 0, n); size += n; if (size > LIMIT) throw new IOException("That file is too large."); } }
                    if (TtrPacks.looksLikePack(extracted)) { String clean = clean(base); place(folder, extracted, clean); added.add(stem(clean)); }
                }
            } finally { extracted.delete(); }
        } catch (java.util.zip.ZipException notZip) { added.clear(); }
        if (added.isEmpty()) throw new IOException("That file isn't a content pack. Packs are .mf files, or a .zip containing them.");
        return added.size() == 1 ? "Added " + added.get(0) + "." : "Added " + added.size() + " packs.";
    }
    private static String stem(String name) { return name.replaceFirst("(?i)\\.mf$", ""); }
    private static void place(File folder, File from, String name) throws IOException {
        File to = new File(folder, clean(name)); if (to.exists() && !to.delete()) throw new IOException("Unable to replace " + to.getName() + ".");
        if (!from.renameTo(to)) throw new IOException("Unable to save " + to.getName() + ".");
    }
}
