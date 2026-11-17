package org.ttrmobile;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Storage measurements for Settings. */
final class TtrUsage {
    private TtrUsage() { }
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
