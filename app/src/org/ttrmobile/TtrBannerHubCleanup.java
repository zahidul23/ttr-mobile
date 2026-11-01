package org.ttrmobile;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * After upgrading from the BannerHub-based build, remove BannerHub's runtime image, databases and
 * settings (several GB). Kept: saved accounts (no_backup + Android Keystore), TTR game files
 * (external storage), the standalone runtime and TTR's own preferences. Runs once.
 */
final class TtrBannerHubCleanup {
    private TtrBannerHubCleanup() { }
    private static final String[] FILES = {"usr", "xj_winemu", "Steam", "steam_games", "steam_download_cache", "Aria", "mmkv",
        "pcLaunchLog", "profiles", "tmp", "temp.xml", "ttr-direct", "ttr-storage-trim.v1"};

    static void clean(Context c) {
        File marker = new File(c.getFilesDir(), "bannerhub-cleanup.v1");
        if (marker.exists()) return;
        try {
            for (String name : FILES) TtrWineRuntime.deleteTree(new File(c.getFilesDir(), name));
            File databases = new File(c.getDataDir(), "databases");
            File[] dbs = databases.listFiles(); if (dbs != null) for (File f : dbs) TtrWineRuntime.deleteTree(f);
            File prefs = new File(c.getDataDir(), "shared_prefs");
            File[] list = prefs.listFiles((d, n) -> !n.startsWith("ttr-") && !n.startsWith("TtrAccounts") && !n.startsWith("ttr_"));
            if (list != null) for (File f : list) Files.deleteIfExists(f.toPath());
            File external = c.getExternalFilesDir(null);
            if (external != null) for (String name : new String[]{"Documents", "Emulator", "GameRom", "log", "logs"}) TtrWineRuntime.deleteTree(new File(external, name));
            marker.createNewFile();
            TtrDiagnostics.log(c, "Removed the previous BannerHub runtime.");
        } catch (IOException e) { TtrDiagnostics.log(c, "BannerHub cleanup incomplete: " + e.getMessage()); }
    }
}
