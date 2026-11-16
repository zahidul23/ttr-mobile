package org.ttrmobile;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** How much of the phone's storage TTR Mobile uses, split the way Settings shows it. */
final class TtrUsage {
    private TtrUsage() { }
    /** Bytes used by { Windows, TTR game files, content packs }. Content packs count both copies (the phone's folder and the game's). */
    static long[] measure(Context c) {
        File files = c.getFilesDir(), game = new File(files, "ttr/game"), resources = new File(game, "resources");
        long windows = 0;
        for (String name : new String[]{"wine-runtime", "wine-prefix", "wine-home", "wine-tmp"}) windows += size(new File(files, name));
        windows += size(new File(c.getNoBackupFilesDir(), "ttr"));
        long copies = size(resources), ttr = size(new File(files, "ttr")) - copies;
        return new long[]{windows, Math.max(0, ttr), size(TtrPacks.folder(c)) + copies};
    }
    /** Total size of the regular files under a folder (links are not followed, so shared files count once). */
    static long size(File dir) {
        final long[] total = {0};
        try {
            if (!dir.exists()) return 0;
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<java.nio.file.Path>() {
                @Override public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes a) { if (a.isRegularFile()) total[0] += a.size(); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(java.nio.file.Path file, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return total[0];
    }
}
