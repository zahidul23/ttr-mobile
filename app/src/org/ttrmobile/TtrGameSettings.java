package org.ttrmobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.json.JSONArray;

/** Change only first-run defaults (controller support, FPS counter), the display fields managed by the mobile viewport, the launcher's volumes, and the game's frame limit (kept off). */
public final class TtrGameSettings {
    private TtrGameSettings() { }
    public static void prepare(Context c, File game) throws Exception {
        prepare(c, game, c.getSharedPreferences("ttr-mobile-defaults", Context.MODE_PRIVATE));
    }
    /** Game volumes set in the launcher (0-100, 50 until changed); they replace TTR's own at every launch. */
    public static final String[][] VOLUMES = {{"music-volume", "Music"}, {"sfx-volume", "Sound effects"}, {"toon-chat-sfx-volume", "Toon chat sounds"}};
    public static android.content.SharedPreferences audio(Context c) { return c.getSharedPreferences("ttr-audio", Context.MODE_PRIVATE); }
    public static int volume(Context c, String key) { return Math.max(0, Math.min(100, audio(c).getInt(key, 50))); }
    static void prepare(Context c, File game, android.content.SharedPreferences defaults) throws Exception {
        File path = new File(game, "settings.json");
        if (path.length() > 131072) throw new IOException("The game settings file is too large.");
        JSONObject settings = path.isFile() ? new JSONObject(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        if (!defaults.getBoolean("controller-v1", false)) {
            JSONObject controller = settings.optJSONObject("controller");
            if (controller == null) { controller = new JSONObject(); settings.put("controller", controller); }
            controller.put("controller-enabled", true);
        }
        JSONObject video = settings.optJSONObject("video");
        if (video == null) { video = new JSONObject(); settings.put("video", video); }
        if (!defaults.getBoolean("show-fps-v1", false)) video.put("show-fps", true); // once; players can turn it off
        video.put("display-mode", "window"); video.put("maximized", false); video.put("forced-aspect-ratio", 0.0);
        video.put("resolution", new JSONArray().put(TtrViewport.renderWidth).put(TtrViewport.renderHeight));
        // TTR's own frame limit stays off: on this runtime it waits by keeping the processor busy, so a limit made the
        // phone hotter, not cooler (measured: about 91% of a core at 60 FPS against 71% unlimited). A limit set in the
        // game's options is undone here too.
        video.put("fps-limit", -1);
        JSONObject sound = settings.optJSONObject("audio");
        if (sound == null) { sound = new JSONObject(); settings.put("audio", sound); }
        for (String[] v : VOLUMES) sound.put(v[0], volume(c, v[0]));
        AtomicFile file = new AtomicFile(path); FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write((settings.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
        defaults.edit().putBoolean("controller-v1", true).putBoolean("show-fps-v1", true).apply();
    }
}
