package org.ttrmobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Corporate Clash updates from its launcher manifests: the Windows and resources manifests list each file's
 * path, SHA-1 and compressed SHA-1; files are gzip downloads from Clash's file server. Both hashes are checked, and
 * unchanged files are skipped using the same bounded verification cache as Rewritten.
 */
public final class TtrClashUpdater {
    static final String MANIFEST = "https://corporateclash.net/api/v1/launcher/manifest/v3/production/";
    static final String FILES = "https://r2prod.corporateclash.net/";
    private static final String[] PLATFORMS = {"windows", "resources"};
    private TtrClashUpdater() { }

    private static final class Entry {
        final String platform, path, hash, packed;
        Entry(String platform, String path, String hash, String packed) { this.platform = platform; this.path = path; this.hash = hash; this.packed = packed; }
        String label() { return path.substring(path.lastIndexOf('/') + 1); }
    }

    public static File update(Context c, boolean force, TtrUpdater.Progress progress) throws Exception {
        File dir = TtrGame.directory(c, TtrGame.CLASH); progress.show("Checking game files…");
        List<Entry> entries = new ArrayList<>(); Set<String> seen = new HashSet<>(); String version = "";
        for (String platform : PLATFORMS) {
            JSONObject manifest = new JSONObject(TtrHttp.get(MANIFEST + platform, 4 * 1024 * 1024));
            if (platform.equals("windows")) version = manifest.optString("manifestVersion", "");
            JSONArray files = manifest.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i); String path = f.getString("filePath");
                TtrPaths.nested(dir, path);
                if (!seen.add(path)) throw new IOException("The Corporate Clash manifest lists a file twice.");
                entries.add(new Entry(platform, path, hash(f.getString("sha1")), hash(f.getString("compressed_sha1"))));
            }
        }
        if (!seen.contains(TtrGame.engine(TtrGame.CLASH))) throw new IOException("The manifest does not contain the Windows game client.");
        File staging = new File(dir, ".ttr-staging"); TtrGame.deleteTree(staging);
        JSONObject cache = cache(c); JSONObject records = cache.optJSONObject("files");
        if (records == null) records = new JSONObject();
        final JSONObject known = records;
        List<Entry> todo = new ArrayList<>();
        for (Entry e : entries) {
            File target = TtrPaths.nested(dir, e.path); JSONObject record = known.optJSONObject(e.path);
            boolean hit = !force && record != null && target.isFile() && e.hash.equals(record.optString("hash"))
                && target.length() == record.optLong("size", -1) && target.lastModified() == record.optLong("modified", -1)
                && System.currentTimeMillis() - record.optLong("checked", 0) < 24L * 60 * 60 * 1000;
            if (!hit) todo.add(e);
        }
        AtomicInteger complete = new AtomicInteger(); progress.amount(0, todo.size());
        ExecutorService pool = Executors.newFixedThreadPool(3); List<Future<?>> work = new ArrayList<>();
        try {
            for (Entry e : todo) work.add(pool.submit(() -> {
                try {
                    TtrHttp.checkCancelled(); File target = TtrPaths.nested(dir, e.path);
                    progress.file(e.path, "Checking " + e.label(), 0);
                    if (!target.isFile() || !e.hash.equals(TtrUpdater.sha1(target))) install(dir, staging, e, progress);
                    synchronized (known) {
                        known.put(e.path, new JSONObject().put("hash", e.hash).put("size", target.length()).put("modified", target.lastModified()).put("checked", System.currentTimeMillis()));
                        cache.put("files", known); saveCache(c, cache);
                    }
                    progress.file(e.path, null, 1);
                    progress.amount(complete.incrementAndGet(), todo.size());
                } catch (Exception x) { throw new CompletionException(x); }
            }));
            for (Future<?> future : work) {
                TtrHttp.checkCancelled();
                try { future.get(); } catch (ExecutionException x) { Throwable cause = x.getCause(); if (cause instanceof CompletionException) cause = cause.getCause(); if (cause instanceof Exception) throw (Exception) cause; throw new IOException("Game update failed."); }
            }
        } finally {
            for (Future<?> future : work) if (!future.isDone()) future.cancel(true);
            pool.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try { if (!pool.awaitTermination(40, TimeUnit.SECONDS)) throw new IOException("Game download cleanup is still running. Reopen the app before retrying."); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            TtrGame.deleteTree(staging);
        }
        TtrHttp.checkCancelled();
        cache.put("release", new JSONObject().put("version", version).put("checked", System.currentTimeMillis()));
        cache.put("completed", System.currentTimeMillis()); saveCache(c, cache);
        progress.show("Game files are up to date."); return dir;
    }

    /** "Corporate Clash · checked <date>" once the files have been checked. */
    public static String release(Context c) {
        JSONObject info = cache(c).optJSONObject("release");
        if (info == null || info.optLong("checked", 0) == 0) return "Corporate Clash files not checked yet";
        return "Corporate Clash · files checked " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(info.optLong("checked")));
    }

    private static void install(File dir, File staging, Entry e, TtrUpdater.Progress progress) throws Exception {
        staging.mkdirs();
        String id = TtrUpdater.hex(MessageDigest.getInstance("SHA-1").digest((e.platform + "/" + e.path).getBytes(StandardCharsets.UTF_8)));
        File packed = new File(staging, id + ".gz"), staged = new File(staging, id + ".staged");
        try {
            String url = FILES + e.platform + "/" + e.path.replace(" ", "%20") + ".gz";
            if (!e.packed.equals(TtrUpdater.acquire(url, packed, progress, e.path, e.label()))) throw new IOException("Downloaded game file failed verification.");
            MessageDigest hash = MessageDigest.getInstance("SHA-1"); long size = Math.max(1, packed.length()), last = 0; long[] read = {0};
            InputStream counted = new FilterInputStream(new FileInputStream(packed)) {
                @Override public int read() throws IOException { int b = super.read(); if (b >= 0) read[0]++; return b; }
                @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) read[0] += n; return n; }
            };
            progress.file(e.path, "Unpacking " + e.label(), .5);
            try (InputStream in = new java.util.zip.GZIPInputStream(new BufferedInputStream(counted, 262144), 262144); FileOutputStream out = new FileOutputStream(staged)) {
                byte[] buffer = new byte[262144]; int n; long total = 0;
                while ((n = in.read(buffer)) != -1) {
                    TtrHttp.checkCancelled(); total += n; if (total > 4L * 1024 * 1024 * 1024) throw new IOException("Game file is unexpectedly large.");
                    hash.update(buffer, 0, n); out.write(buffer, 0, n);
                    if (System.currentTimeMillis() - last > 500) { last = System.currentTimeMillis(); progress.file(e.path, "Unpacking " + e.label() + " · " + total / 1048576 + " MB", .5 + .5 * Math.min(1, (double) read[0] / size)); }
                }
                out.getFD().sync();
            }
            if (!e.hash.equals(TtrUpdater.hex(hash.digest()))) throw new IOException("Unpacked game file failed verification.");
            TtrHttp.checkCancelled();
            File target = TtrPaths.nested(dir, e.path); File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Unable to create game storage.");
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException x) {
            if (x instanceof InterruptedIOException || x.getMessage() != null && x.getMessage().startsWith("Not enough storage")) throw x;
            throw new IOException("Unable to download or verify " + e.label() + ". Retry when connected.");
        } finally { packed.delete(); staged.delete(); }
    }
    private static String hash(String value) throws IOException { if (!value.matches("[a-f0-9]{40}")) throw new IOException("Invalid game manifest hash."); return value; }
    private static JSONObject cache(Context c) {
        try { return new JSONObject(new String(new AtomicFile(new File(c.getNoBackupFilesDir(), TtrGame.cacheName(TtrGame.CLASH))).readFully(), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }
    private static void saveCache(Context c, JSONObject cache) throws Exception {
        AtomicFile file = new AtomicFile(new File(c.getNoBackupFilesDir(), TtrGame.cacheName(TtrGame.CLASH))); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(cache.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
}
