package org.ttrmobile;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/**
 * The games this launcher can play: Toontown Rewritten and Toontown: Corporate Clash. Each has its own accounts,
 * game files and update service; Windows is shared. Only the chosen game is downloaded.
 */
public final class TtrGame {
    public static final String TTR = "ttr", CLASH = "clash";
    public static final String[] ALL = {TTR, CLASH};
    private static final String PREFS = "ttr-game", KEY = "game";
    private TtrGame() { }

    public static String name(String game) { return CLASH.equals(game) ? "Corporate Clash" : "Toontown Rewritten"; }
    public static String fullName(String game) { return CLASH.equals(game) ? "Toontown: Corporate Clash" : "Toontown Rewritten"; }
    /** Windows program the bootstrap starts. */
    public static String engine(String game) { return CLASH.equals(game) ? "CorporateClash.exe" : TtrPlatform.ENGINE; }
    /** Rough first download (compressed), shown before the player chooses to download. */
    public static String downloadSize(String game) { return CLASH.equals(game) ? "about 1.7 GB" : "about 500 MB"; }

    /**
     * The game picked on the launcher screen, or "" when none has been picked yet. Installs from before Clash
     * support already have Rewritten files, so they continue with Rewritten.
     */
    public static String current(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String game = p.getString(KEY, "");
        if (game.equals(TTR) || game.equals(CLASH)) return game;
        if (installed(c, TTR)) { p.edit().putString(KEY, TTR).apply(); return TTR; }
        return "";
    }
    public static boolean chosen(Context c) { return !current(c).isEmpty(); }
    /** Current game, or Rewritten while none is picked (for code paths that need one). */
    public static String active(Context c) { String g = current(c); return g.isEmpty() ? TTR : g; }
    public static void choose(Context c, String game) {
        if (!TTR.equals(game) && !CLASH.equals(game)) throw new IllegalArgumentException(game);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, game).commit();
    }

    /** Game files folder in internal storage (Rewritten keeps its original location). */
    public static File directory(Context c, String game) throws Exception {
        if (CLASH.equals(game)) {
            File dir = new File(c.getFilesDir(), "clash/game");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Unable to create game storage.");
            return dir;
        }
        return TtrUpdater.gameDirectory(c);
    }
    /** Where the folder would be, without creating it. */
    static File location(Context c, String game) { return new File(c.getFilesDir(), CLASH.equals(game) ? "clash/game" : "ttr/game"); }
    /** True once the game's program has been downloaded (later updates may still be due). */
    public static boolean installed(Context c, String game) { return new File(location(c, game), engine(game)).isFile(); }
    /** Installed, or a first download got part way (it then continues on its own). */
    public static boolean started(Context c, String game) { return installed(c, game) || new File(c.getNoBackupFilesDir(), cacheName(game)).isFile(); }

    /** Bytes used by the game's files. */
    public static long size(Context c, String game) { return du(location(c, game)); }
    static long du(File f) {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return 0;
        File[] children = f.listFiles();
        if (children == null) return f.isFile() ? f.length() : 0;
        long total = 0; for (File child : children) total += du(child); return total;
    }

    /** Removes a game's files and its update records; saved accounts stay. */
    public static void delete(Context c, String game) {
        deleteTree(location(c, game));
        new File(c.getNoBackupFilesDir(), cacheName(game)).delete();
        new File(c.getNoBackupFilesDir(), cacheName(game) + ".bak").delete();
    }
    /** Update records: which files were verified and when. */
    static String cacheName(String game) { return CLASH.equals(game) ? "clash-cache.v1" : "game-cache.v1"; }
    static void deleteTree(File f) {
        if (!java.nio.file.Files.isSymbolicLink(f.toPath())) { File[] children = f.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        f.delete();
    }
}
