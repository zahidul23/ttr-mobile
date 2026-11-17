package org.ttrmobile;

import android.content.Context;
import android.os.FileObserver;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * "Open Folder" in the game's Content Packs menu. Windows is asked to open the resources folder; libttrexec answers
 * that launch by writing the folder into FILE (see ttrexec.c). The watch is an inotify one, so nothing runs
 * between requests.
 */
final class TtrFolderRequest {
    private TtrFolderRequest() { }
    static final String FILE = "folder-request";
    interface Listener { void opened(String folder); }
    private static FileObserver observer;

    static synchronized void start(Context c, Listener listener) {
        stop();
        try {
            File dir = TtrRuntime.directory(c), file = new File(dir, FILE); file.delete();
            observer = new FileObserver(dir, FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
                @Override public void onEvent(int event, String name) {
                    if (!FILE.equals(name)) return;
                    try {
                        String folder = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim(); file.delete();
                        if (!folder.isEmpty()) listener.opened(folder);
                    } catch (Exception ignored) { }
                }
            };
            observer.startWatching();
        } catch (Exception e) { android.util.Log.w("TtrFolderRequest", "Not watching for folder requests: " + e.getMessage()); }
    }
    static synchronized void stop() { if (observer != null) { observer.stopWatching(); observer = null; } }
}
