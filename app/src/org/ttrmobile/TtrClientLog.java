package org.ttrmobile;

import android.content.Context;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Follows TTR's own client log (logs/ttr-*.log in the game directory, same format on every
 * platform) to report how far the client has started. Only fixed milestone strings are matched;
 * no log text is retained or shown.
 */
public final class TtrClientLog {
    /** Ordered milestones up to the moment the game window appears. */
    static final String[] MARKERS = {
        "TTLocalizer: Running in language", "ToontownStart: Starting the game", "Default graphics pipe", "Successfully opened window"
    };
    static final String[] LABELS = {
        "Loading game data", "Starting the game", "Starting graphics", "Opening the game window"
    };
    private TtrClientLog() { }

    /**
     * Milestones reached in the newest log written since {@code sinceMillis}: -1 when no such log
     * exists yet, 0 when it exists without any milestone, otherwise 1..MARKERS.length.
     */
    static int reached(Context c, long sinceMillis) {
        try {
            File[] logs = new File(TtrUpdater.gameDirectory(c), "logs").listFiles((d, n) -> n.startsWith("ttr-") && n.endsWith(".log"));
            File newest = null;
            if (logs != null) for (File f : logs) if (f.lastModified() >= sinceMillis - 2000 && (newest == null || f.lastModified() > newest.lastModified())) newest = f;
            if (newest == null) return -1;
            String text;
            try (RandomAccessFile in = new RandomAccessFile(newest, "r")) {
                byte[] b = new byte[(int) Math.min(in.length(), 262144)]; in.readFully(b);
                text = new String(b, StandardCharsets.ISO_8859_1);
            }
            for (int i = MARKERS.length - 1; i >= 0; i--) if (text.contains(MARKERS[i])) return i + 1;
            return 0;
        } catch (Exception e) { return -1; }
    }
}
