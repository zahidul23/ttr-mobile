package org.ttrmobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Content packs: the .mf files players put in resources/. They live in the app's shared Android/data folder, where any
 * file manager can add them, and are copied into the game's own resources folder (private storage) each time the game
 * starts. Name, description and icon come from the pack itself (info.ini and pack.png); whether a pack is on, and the
 * order it loads in, are the game's own settings.json entries, so the launcher and the in-game list always agree.
 */
public final class TtrPacks {
    private TtrPacks() { }

    public static final class Pack {
        public final String file; public final long size; final long stamp;
        public String name, description; public byte[] icon; public boolean enabled = true;
        Pack(String file, long size, long stamp) { this.file = file; this.size = size; this.stamp = stamp; name = file.replaceFirst("(?i)\\.mf$", ""); description = ""; }
    }

    private static final int MAX_INFO = 65536, MAX_ICON = 524288, MAX_ENTRIES = 100000;
    private static final Map<String, Pack> parsed = new HashMap<>();

    /** A size for people: 912 KB, 3.4 MB, 1.25 GB. */
    public static String readable(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.US, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(java.util.Locale.US, "%.1f MB", bytes / (double) (1L << 20));
        return Math.max(1, bytes / 1024) + " KB";
    }

    /** The folder players add packs to (Android/data/&lt;package&gt;/files/resources). */
    public static File folder(Context c) {
        File base = c.getExternalFilesDir(null);
        File dir = new File(base != null ? base : c.getFilesDir(), "resources");
        dir.mkdirs();
        return dir;
    }
    private static File settings(Context c) throws Exception { return new File(TtrUpdater.gameDirectory(c), "settings.json"); }
    static boolean isPack(String name) { return name.toLowerCase(java.util.Locale.ROOT).endsWith(".mf") && !name.startsWith(".") && !name.matches(".*[<>:\"|?*\\\\/].*"); }

    /** Installed packs in load order, with their on/off state. Reads each pack's index only, never the whole file. */
    public static synchronized List<Pack> list(Context c) {
        File[] files = folder(c).listFiles((d, n) -> isPack(n));
        List<Pack> all = new ArrayList<>();
        if (files == null) return all;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            Pack known = parsed.get(f.getName());
            if (known == null || known.size != f.length() || known.stamp != f.lastModified()) {
                Pack fresh = new Pack(f.getName(), f.length(), f.lastModified());
                try { describe(f, fresh); } catch (Exception ignored) { }
                parsed.put(f.getName(), fresh); known = fresh;
            }
            all.add(known);
        }
        List<String> order = new ArrayList<>(), disabled = new ArrayList<>();
        try { JSONObject video = video(readSettings(settings(c))); order = names(video.optJSONArray("content-pack-order")); disabled = names(video.optJSONArray("content-packs-disabled")); } catch (Exception ignored) { }
        List<Pack> sorted = new ArrayList<>();
        for (String entry : order) for (Pack p : all) if (entry.equals(entry(p)) && !sorted.contains(p)) sorted.add(p);
        for (Pack p : all) if (!sorted.contains(p)) sorted.add(p);
        for (Pack p : sorted) p.enabled = !disabled.contains(entry(p));
        return sorted;
    }
    /** How the game's settings name a pack: its file name without ".mf" (an older entry with ".mf" still reads the same). */
    static String entry(Pack p) { return stem(p.file); }
    private static String stem(String name) { return name.replaceFirst("(?i)\\.mf$", ""); }
    private static List<String> names(JSONArray array) { List<String> list = new ArrayList<>(); for (String s : strings(array)) if (!list.contains(stem(s))) list.add(stem(s)); return list; }

    public static synchronized void setEnabled(Context c, Pack pack, boolean on) throws Exception {
        File path = settings(c); JSONObject settings = readSettings(path); JSONObject video = video(settings);
        List<String> disabled = names(video.optJSONArray("content-packs-disabled")); disabled.remove(entry(pack));
        if (!on) disabled.add(entry(pack));
        video.put("content-packs-disabled", new JSONArray(disabled)); write(path, settings);
    }
    /** Moves a pack to a place in the load order (0 is first); packs earlier in the list take priority. */
    public static synchronized void reorder(Context c, Pack pack, int to) throws Exception {
        List<String> names = new ArrayList<>(); for (Pack p : list(c)) names.add(entry(p));
        int from = names.indexOf(entry(pack));
        if (from < 0 || to < 0 || to >= names.size() || to == from) return;
        names.add(to, names.remove(from));
        File path = settings(c); JSONObject settings = readSettings(path);
        video(settings).put("content-pack-order", new JSONArray(names)); write(path, settings);
    }
    /** Copies the packs into the running game's folder (its Refresh All picks them up). */
    public static void syncNow(Context c) {
        try { sync(c, TtrUpdater.gameDirectory(c)); } catch (Exception e) { android.util.Log.w("TtrPacks", "Content packs were not copied: " + e.getMessage()); }
    }
    public static synchronized void delete(Context c, Pack pack) throws Exception {
        File f = new File(folder(c), pack.file); parsed.remove(pack.file);
        if (f.exists() && !f.delete()) throw new IOException("Unable to delete " + pack.file + ".");
        new File(new File(TtrUpdater.gameDirectory(c), "resources"), pack.file).delete();
        File path = settings(c); JSONObject settings = readSettings(path); JSONObject video = video(settings);
        for (String key : new String[]{"content-pack-order", "content-packs-disabled"}) {
            List<String> values = names(video.optJSONArray(key)); if (values.remove(entry(pack))) video.put(key, new JSONArray(values));
        }
        write(path, settings);
    }

    /** Copies new and changed packs into the game's resources folder and removes the ones taken out. */
    public static synchronized void sync(Context c, File game) throws IOException {
        File target = new File(game, "resources"); target.mkdirs();
        File[] source = folder(c).listFiles((d, n) -> isPack(n)); Set<String> keep = new HashSet<>();
        if (source != null) for (File from : source) {
            if (!from.isFile()) continue;
            keep.add(from.getName()); File to = new File(target, from.getName());
            if (to.isFile() && to.length() == from.length() && to.lastModified() == from.lastModified()) continue;
            File part = new File(target, from.getName() + ".part");
            try (FileChannel in = new java.io.FileInputStream(from).getChannel(); FileChannel out = new FileOutputStream(part).getChannel()) {
                long done = 0, size = in.size(); while (done < size) done += in.transferTo(done, size - done, out);
            }
            part.setLastModified(from.lastModified());
            if (!part.renameTo(to)) { part.delete(); throw new IOException("Unable to install " + from.getName() + "."); }
        }
        File[] old = target.listFiles();
        if (old != null) for (File f : old) if ((isPack(f.getName()) && !keep.contains(f.getName())) || f.getName().endsWith(".mf.part")) f.delete();
    }
    private static List<String> strings(JSONArray array) {
        List<String> list = new ArrayList<>(); if (array != null) for (int i = 0; i < array.length(); i++) list.add(array.optString(i));
        return list;
    }
    private static JSONObject readSettings(File path) throws Exception {
        if (!path.isFile()) return new JSONObject();
        if (path.length() > 131072) throw new IOException("The game settings file is too large.");
        return new JSONObject(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
    }
    private static JSONObject video(JSONObject settings) throws Exception {
        JSONObject video = settings.optJSONObject("video");
        if (video == null) { video = new JSONObject(); settings.put("video", video); }
        return video;
    }
    private static void write(File path, JSONObject settings) throws Exception {
        AtomicFile file = new AtomicFile(path); FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write((settings.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }

    // ---- Panda3D Multifile (version 1.x): only what is needed to read a pack's info.ini and pack.png ----

    private static final class Entry { String name; long start, length, original; int flags; }

    /** True when the file starts like a Panda3D multifile (the format of content packs). */
    public static boolean looksLikePack(File f) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) { return header(in) >= 0; } catch (IOException e) { return false; }
    }
    /** Offset of the multifile's magic (a launcher line may come before it), or -1. */
    private static long header(RandomAccessFile in) throws IOException {
        byte[] head = new byte[1024]; int n = in.read(head); if (n < 14) return -1;
        byte[] magic = {'p', 'm', 'f', 0, '\n', '\r'};
        for (int i = 0; i + 14 <= n; i++) { boolean same = true; for (int k = 0; k < 6 && same; k++) same = head[i + k] == magic[k]; if (same) return i; }
        return -1;
    }
    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static long u32(byte[] b, int o) { return (b[o] & 255L) | (b[o + 1] & 255L) << 8 | (b[o + 2] & 255L) << 16 | (b[o + 3] & 255L) << 24; }

    private static List<Entry> index(RandomAccessFile in) throws IOException {
        long base = header(in); if (base < 0) throw new IOException("Not a content pack.");
        byte[] h = new byte[14]; in.seek(base); in.readFully(h);
        int major = u16(h, 6), minor = u16(h, 8); long scale = u32(h, 10);
        if (major != 1 || scale < 1) throw new IOException("Unsupported content pack version.");
        long p = base + 14 + (minor >= 1 ? 4 : 0), length = in.length(); List<Entry> list = new ArrayList<>();
        for (int count = 0; count < MAX_ENTRIES; count++) {
            if (p + 4 > length) throw new IOException("Damaged content pack.");
            byte[] b = new byte[18]; in.seek(p); in.readFully(b, 0, 4);
            long next = u32(b, 0) * scale; if (next == 0) break;
            in.readFully(b, 4, 10); Entry e = new Entry();
            e.start = base + u32(b, 4) * scale; e.length = u32(b, 8); e.flags = u16(b, 12); e.original = e.length;
            if ((e.flags & 0x18) != 0) { byte[] o = new byte[4]; in.readFully(o); e.original = u32(o, 0); }
            if (minor >= 1) in.skipBytes(4);
            byte[] n = new byte[2]; in.readFully(n); byte[] name = new byte[u16(n, 0)]; in.readFully(name);
            for (int i = 0; i < name.length; i++) name[i] ^= (byte) 0xff;
            e.name = new String(name, StandardCharsets.UTF_8);
            if ((e.flags & 1) == 0) list.add(e);
            if (next <= p) throw new IOException("Damaged content pack.");
            p = next;
        }
        return list;
    }
    private static byte[] read(RandomAccessFile in, Entry e, int limit) throws IOException, DataFormatException {
        if ((e.flags & 0x10) != 0 || e.original > limit || e.start + e.length > in.length()) return null;
        byte[] data = new byte[(int) e.length]; in.seek(e.start); in.readFully(data);
        if ((e.flags & 8) == 0) return data;
        Inflater inflater = new Inflater(); ByteArrayOutputStream out = new ByteArrayOutputStream((int) e.original);
        try {
            inflater.setInput(data); byte[] buffer = new byte[8192];
            while (!inflater.finished()) { int n = inflater.inflate(buffer); if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break; out.write(buffer, 0, n); if (out.size() > limit) return null; }
        } finally { inflater.end(); }
        return out.toByteArray();
    }
    private static void describe(File f, Pack pack) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            for (Entry e : index(in)) {
                if (e.name.equals("info.ini")) { byte[] text = read(in, e, MAX_INFO); if (text != null) info(new String(text, StandardCharsets.UTF_8), pack); }
                else if (e.name.equals("pack.png")) pack.icon = read(in, e, MAX_ICON);
            }
        }
    }
    /** info.ini: a [PackInfo] section with name= and description=. */
    private static void info(String text, Pack pack) {
        boolean section = false;
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("[")) { section = line.equalsIgnoreCase("[PackInfo]"); continue; }
            int eq = line.indexOf('='); if (!section || eq < 1 || line.startsWith("#") || line.startsWith(";")) continue;
            String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            if (key.equalsIgnoreCase("name") && !value.isEmpty()) pack.name = value;
            else if (key.equalsIgnoreCase("description")) pack.description = value;
        }
    }
}
