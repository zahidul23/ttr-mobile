package org.ttrmobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official win64 updates: two bounded workers, native extraction and streaming hashes. */
public final class TtrUpdater {
    public interface Progress {
        void show(String message); default void amount(int complete,int total){}
        /** One file's current activity ("Downloading …", "Unpacking …") and its progress 0..1; null when it is done. */
        default void file(String name, String line, double fraction){}
    }
    private static final class Mirrors {
        private List<String> values;
        synchronized List<String> get() throws Exception {
            if(values!=null)return values;
            JSONArray list=new JSONArray(TtrHttp.get(TtrHttp.MIRRORS,65536));List<String> result=new ArrayList<>();
            for(int i=0;i<list.length();i++){String mirror=list.getString(i);TtrHttp.official(mirror);if(!mirror.endsWith("/"))throw new IOException("Invalid official download mirror.");result.add(mirror);}
            if(result.isEmpty())throw new IOException("No official game download mirror is available.");values=result;return values;
        }
    }
    private TtrUpdater() { }
    /**
     * Game files live in internal app storage. They used to be in the shared Android/data folder, but on Android 17
     * (Galaxy S23) the system's storage daemon force-unmounts that folder while the game starts, and ends every
     * process with a file open there (interrupt, terminate, then kill 5 s apart): the game, its console and wineserver.
     * Files from the old folder are moved over once; anything that cannot be moved is fetched again by the updater.
     */
    public static synchronized File gameDirectory(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), "ttr/game");
        if (dir.isDirectory()) return dir;
        File external = c.getExternalFilesDir(null), old = external == null ? null : new File(external, "ttr/game");
        if (old != null && old.isDirectory()) {
            File staging = new File(c.getFilesDir(), "ttr/game-moving"); delete(staging);
            move(old, staging);
            if (!staging.renameTo(dir)) throw new IOException("Unable to move game storage.");
            delete(old);
        } else if (!dir.mkdirs()) throw new IOException("Unable to create game storage.");
        return dir;
    }
    private static void move(File from, File to) {
        if (from.isDirectory()) {
            to.mkdirs(); File[] children = from.listFiles();
            if (children != null) for (File child : children) move(child, new File(to, child.getName()));
        } else try {
            java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            to.setLastModified(from.lastModified());
        } catch (IOException ignored) { }
    }
    private static void delete(File f) {
        File[] children = f.listFiles(); if (children != null) for (File child : children) delete(child);
        f.delete();
    }
    public static File update(Context c, Progress p) throws Exception { return update(c, TtrHttp.MANIFEST, false, p); }
    public static File update(Context c, String url, Progress p) throws Exception { return update(c, url, false, p); }
    public static File update(Context c, boolean force, Progress p) throws Exception { return update(c, TtrHttp.MANIFEST, force, p); }
    private static JSONObject cache(Context c) {
        try { return new JSONObject(new String(new AtomicFile(new File(c.getNoBackupFilesDir(), "game-cache.v1")).readFully(), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }
    private static void saveCache(Context c, JSONObject cache) throws Exception {
        AtomicFile file = new AtomicFile(new File(c.getNoBackupFilesDir(), "game-cache.v1")); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(cache.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
    public static String release(Context c) {
        JSONObject info = cache(c).optJSONObject("release");
        if (info == null) return "TTR release information unavailable";
        return "TTR " + info.optString("version") + (info.optString("date").isEmpty() ? "" : " · " + info.optString("date"));
    }
    private static JSONObject latestRelease() {
        try {
            JSONArray notes = new JSONArray(TtrHttp.get("https://www.toontownrewritten.com/api/releasenotes", 1024 * 1024));
            // API order is publication order; IDs and numeric versions are not chronological.
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.getJSONObject(i); String slug = note.optString("slug");
                if (slug.matches("ttr-live-v[0-9]+(\\.[0-9]+)+[a-z]?"))
                    return new JSONObject().put("version", slug.substring("ttr-live-".length())).put("date", note.optString("date"));
            }
        } catch (Exception ignored) { }
        return null;
    }
    private static File update(Context c, String manifestUrl, boolean force, Progress progress) throws Exception {
        File dir = gameDirectory(c); progress.show("Checking game files…");
        JSONObject manifest = new JSONObject(TtrHttp.get(manifestUrl, 4 * 1024 * 1024));
        Mirrors mirrors=new Mirrors(); // Cached/unchanged files need no mirror request.
        JSONObject cache = cache(c); JSONObject records = cache.optJSONObject("files");
        if (records == null) records = new JSONObject();
        List<String> names = new ArrayList<>(); Iterator<String> keys = manifest.keys();
        while (keys.hasNext()) {
            String name = keys.next(); JSONArray only = manifest.getJSONObject(name).getJSONArray("only");
            for (int i = 0; i < only.length(); i++) if (only.getString(i).equals(TtrPlatform.PATCH)) { TtrPaths.child(dir, name); names.add(name); break; }
        }
        if (!names.contains(TtrPlatform.ENGINE)) throw new IOException("The manifest does not contain the " + TtrPlatform.RUNTIME + " game client.");
        Collections.sort(names); final JSONObject entries = records;
        List<String> todo = new ArrayList<>();
        for (String name : names) {
            File target = TtrPaths.child(dir, name); String expected = hashValue(manifest.getJSONObject(name).getString("hash"));
            JSONObject record = entries.optJSONObject(name);
            boolean hit = !force && record != null && target.isFile() && expected.equals(record.optString("hash"))
                && target.length() == record.optLong("size", -1) && target.lastModified() == record.optLong("modified", -1)
                && System.currentTimeMillis() - record.optLong("checked", 0) < 24L * 60 * 60 * 1000;
            if (!hit) todo.add(name);
        }
        java.util.concurrent.atomic.AtomicInteger complete=new java.util.concurrent.atomic.AtomicInteger();progress.amount(0,todo.size());
        ExecutorService pool = Executors.newFixedThreadPool(2); List<Future<?>> work = new ArrayList<>();
        try {
            for (int i = 0; i < todo.size(); i++) {
                final String name = todo.get(i), label = name;
                final JSONObject item = manifest.getJSONObject(name);
                work.add(pool.submit(() -> {
                    try {
                        TtrHttp.checkCancelled(); File target = TtrPaths.child(dir, name); String expected = hashValue(item.getString("hash"));
                        progress.file(name, "Checking " + label, 0);
                        if (!target.isFile() || !expected.equals(sha1(target))) install(dir, name, item, mirrors.get(), progress, label);
                        synchronized (entries) {
                            entries.put(name, new JSONObject().put("hash", expected).put("size", target.length()).put("modified", target.lastModified()).put("checked", System.currentTimeMillis()));
                            cache.put("files", entries); saveCache(c, cache);
                        }
                        progress.file(name, null, 1);
                        progress.amount(complete.incrementAndGet(),todo.size());
                    } catch (Exception e) { throw new CompletionException(e); }
                }));
            }
            for (Future<?> future : work) {
                TtrHttp.checkCancelled();
                try { future.get(); } catch (ExecutionException e) { Throwable cause = e.getCause(); if (cause instanceof CompletionException) cause = cause.getCause(); if (cause instanceof Exception) throw (Exception)cause; throw new IOException("Game update failed."); }
            }
        } finally {
            for (Future<?> future : work) if (!future.isDone()) future.cancel(true);
            pool.shutdownNow();
            // Prevent a retry racing worker cleanup or writing a stale cache entry.
            boolean interrupted = Thread.interrupted();
            try { if (!pool.awaitTermination(40, TimeUnit.SECONDS)) throw new IOException("Game download cleanup is still running. Reopen the app before retrying."); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        TtrHttp.checkCancelled();long releaseAge=System.currentTimeMillis()-cache.optLong("release_checked",0);
        if(force||cache.optJSONObject("release")==null||releaseAge<0||releaseAge>4L*60*60*1000){
            JSONObject release=latestRelease();if(release!=null){cache.put("release",release);cache.put("release_checked",System.currentTimeMillis());}
        }
        cache.put("completed", System.currentTimeMillis()); saveCache(c, cache);
        progress.show("Game files are up to date."); return dir;
    }
    private static void install(File dir, String name, JSONObject item, List<String> mirrors, Progress progress, String label) throws Exception {
        String download = item.getString("dl"), expected = hashValue(item.getString("hash")), compressed = hashValue(item.getString("compHash"));
        if (!download.matches("[A-Za-z0-9_% .-]{1,240}") || download.contains("..")) throw new IOException("Unsafe game download name.");
        File packed = new File(dir, "." + name + ".download"), staged = new File(dir, "." + name + ".staged");
        try {
            for (String mirror : mirrors) {
                try {
                    if (!compressed.equals(acquire(mirror + download.replace(" ", "%20"), packed, progress, name, label))) throw new IOException("Downloaded game file failed verification.");
                    MessageDigest hash = MessageDigest.getInstance("SHA-1"); final long[] last = {0}, read = {0}; final long packedSize = Math.max(1, packed.length());
                    // Unpacking progress: how much of the downloaded (compressed) file has been read.
                    InputStream counted = new java.io.FilterInputStream(new FileInputStream(packed)) {
                        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) read[0]++; return b; }
                        @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) read[0] += n; return n; }
                    };
                    progress.file(name, "Unpacking " + label, .5);
                    try (InputStream in = new BufferedInputStream(counted); FileOutputStream out = new FileOutputStream(staged)) {
                        TtrBzip.unpack(in, out, hash, bytes -> { if (System.currentTimeMillis() - last[0] > 500) { progress.file(name, "Unpacking " + label + " · " + bytes / 1048576 + " MB", .5 + .5 * Math.min(1, (double) read[0] / packedSize)); last[0] = System.currentTimeMillis(); } });
                        out.getFD().sync();
                    }
                    if (!expected.equals(hex(hash.digest()))) throw new IOException("Unpacked game file failed verification.");
                    TtrHttp.checkCancelled(); Files.move(staged.toPath(), TtrPaths.child(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING); return;
                } catch (Exception e) { TtrHttp.checkCancelled(); if (mirrors.size() == 1 && e.getClass() == IOException.class) throw e; }
            }
            throw new IOException("Unable to download or verify " + name + ". Retry when connected.");
        } finally { packed.delete(); staged.delete(); }
    }
    private static String acquire(String url, File file, Progress progress, String name, String label) throws Exception {
        HttpsURLConnection c = TtrHttp.open(url); MessageDigest hash = MessageDigest.getInstance("SHA-1");
        try {
            if (c.getResponseCode() != 200) throw new IOException("Game download unavailable.");
            long size = c.getContentLengthLong();
            if (size > file.getParentFile().getUsableSpace() - 64L * 1024 * 1024) throw new IOException("Not enough storage for game updates.");
            try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                byte[] buffer = new byte[262144]; int n; long total = 0, last = 0;
                while ((n = in.read(buffer)) != -1) {
                    TtrHttp.checkCancelled(); total += n; if (total > 2L * 1024 * 1024 * 1024) throw new IOException("Game download is unexpectedly large.");
                    hash.update(buffer, 0, n); out.write(buffer, 0, n);
                    if (System.currentTimeMillis() - last > 500) { progress.file(name, "Downloading " + label + " · " + total / 1048576 + (size > 0 ? " of " + size / 1048576 : "") + " MB", size > 0 ? .5 * total / size : 0); last = System.currentTimeMillis(); }
                }
            }
            return hex(hash.digest());
        } finally { c.disconnect(); }
    }
    private static String hashValue(String value) throws IOException { if (!value.matches("[a-f0-9]{40}")) throw new IOException("Invalid game manifest hash."); return value; }
    public static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    public static String sha1(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { byte[] b = new byte[262144]; int n; while ((n = in.read(b)) != -1) { TtrHttp.checkCancelled(); hash.update(b, 0, n); } }
        return hex(hash.digest());
    }
}
