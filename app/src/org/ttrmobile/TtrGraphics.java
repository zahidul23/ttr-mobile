package org.ttrmobile;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Graphics path for the game. "Faster" runs OpenGL on Vulkan: Mesa zink with the Khronos loader (built from source by
 * tools/vulkan_stack/build.sh and shipped in the APK as assets/runtime/vulkan-stack.tar.zst). On Qualcomm Adreno it
 * runs on Turnip, Mesa's own Adreno Vulkan driver from the same build. On other GPUs it runs on GameNative's
 * open-source Vulkan wrapper (downloaded once, pinned by SHA-256), which drives the phone's own Vulkan driver.
 * "Compatibility" is GL4ES on the phone's OpenGL ES. Automatic uses the faster path where it helps and falls back to
 * compatibility for good if the game cannot start with it.
 *
 * Files live in no_backup/ttr/graphics. A folder there without our marker is a developer's hand-made stack: it is
 * always used and never replaced.
 */
final class TtrGraphics {
    private TtrGraphics() { }
    static final String AUTO = "auto", VULKAN = "vulkan", COMPAT = "compat";
    static final String WRAPPER_URL = "https://downloads.gamenative.app/graphics_driver/wrapper-gamenative-20260925.tzst";
    static final String WRAPPER_SHA256 = "2f584bf314d9857546a634209f80a75f7046f350431d1bb5c1b98516ec82028b";
    static final long WRAPPER_SIZE = 774855L;
    private static final String ASSET = "runtime/vulkan-stack.tar.zst", MARKER = ".ttr-graphics";

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("ttr-graphics", 0); }
    static String mode(Context c) { return prefs(c).getString("mode", AUTO); }
    static void setMode(Context c, String mode) { prefs(c).edit().putString("mode", mode).putBoolean("vulkan-failed", false).apply(); }
    /** Automatic mode gave up on Vulkan on this phone. */
    static boolean failed(Context c) { return prefs(c).getBoolean("vulkan-failed", false); }
    static File directory(Context c) throws Exception { return new File(TtrRuntime.directory(c), "graphics"); }
    private static boolean wanted(Context c) { String m = mode(c); return m.equals(VULKAN) || (m.equals(AUTO) && !failed(c) && !adreno()); }
    /**
     * Qualcomm Adreno: Turnip works, but Qualcomm's OpenGL ES driver is already fast with GL4ES (Moto G Stylus 5G 2023:
     * both 27-28 FPS in a playground), so Automatic keeps compatibility there. PowerVR (MediaTek) doubles with Vulkan.
     */
    static boolean adreno() { return "adreno".equals(property("ro.hardware.vulkan")) || "adreno".equals(property("ro.hardware.egl")); }
    private static String property(String name) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name); } catch (Exception e) { return ""; }
    }
    /** Mesa's messages when zink cannot run the game on this driver. */
    static boolean logShowsFailure(Context c) {
        try {
            String tail = TtrDiagnostics.filtered(new File(c.getCacheDir(), "wine.log"), 20000);
            return tail.contains("vkCreateGraphicsPipelines failed") || tail.contains("failed to load driver: zink") || tail.contains("zink: could not create device");
        } catch (Exception e) { return false; }
    }
    private static boolean developerStack(File dir) { return new File(dir, "lib").isDirectory() && !new File(dir, MARKER).isFile(); }

    /** Whether Windows should start with the Vulkan path (read when the environment is built). */
    static boolean active(Context c) {
        try {
            File dir = directory(c);
            if (developerStack(dir)) return true;
            return wanted(c) && version(c).equals(read(new File(dir, MARKER)));
        } catch (Exception e) { return false; }
    }

    /** Before Windows starts: installs or updates the stack when it is wanted. Failure only means GL4ES is used. */
    static void prepare(Context c) {
        try {
            File dir = directory(c);
            if (!wanted(c) || developerStack(dir) || version(c).equals(read(new File(dir, MARKER)))) return;
            install(c, dir);
            TtrDiagnostics.log(c, "Installed the Vulkan graphics stack (" + version(c) + ").");
        } catch (Exception e) {
            TtrDiagnostics.log(c, "Vulkan graphics unavailable, using compatibility graphics: " + e);
        }
    }

    /** The game could not run on Vulkan (closed right away, or Mesa reported a failure): switch to GL4ES for good. */
    static boolean recordStartupFailure(Context c) {
        if (mode(c).equals(COMPAT) || (mode(c).equals(AUTO) && failed(c))) return false;
        try { if (developerStack(directory(c))) return false; } catch (Exception e) { return false; }
        // Also when Faster was chosen by hand: back to Automatic, which now stays on compatibility here.
        prefs(c).edit().putString("mode", AUTO).putBoolean("vulkan-failed", true).apply();
        TtrDiagnostics.log(c, "The game could not run on Vulkan graphics; switched to compatibility graphics.");
        return true;
    }

    private static String version(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("runtime/vulkan-stack.sha256")) {
            byte[] b = new byte[64]; int n = in.read(b);
            return new String(b, 0, Math.max(0, n), StandardCharsets.US_ASCII).trim().substring(0, 16) + "-" + (adreno() ? "turnip" : WRAPPER_SHA256.substring(0, 16));
        }
    }
    private static String read(File f) { try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim(); } catch (IOException e) { return ""; } }

    private static synchronized void install(Context c, File dir) throws Exception {
        File work = new File(c.getCacheDir(), "graphics-install"); TtrWineRuntime.deleteTree(work); work.mkdirs();
        File staging = new File(dir.getParentFile(), "graphics.partial"); TtrWineRuntime.deleteTree(staging); staging.mkdirs();
        try {
            // Our Mesa and Vulkan loader, from the APK.
            File zst = new File(work, "stack.tar.zst"), tar = new File(work, "stack.tar");
            try (InputStream in = c.getAssets().open(ASSET); OutputStream out = new FileOutputStream(zst)) {
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            if (TtrZstd.decompress(zst.getAbsolutePath(), tar.getAbsolutePath()) != 0) throw new IOException("graphics package could not be decompressed");
            TtrTar.extract(tar, staging, name -> name.startsWith("lib/") || name.startsWith("licenses/") ? name : null);
            String driver;
            if (adreno()) driver = "libvulkan_freedreno.so"; // Turnip, from the package
            else { // GameNative's Vulkan wrapper and its helper libraries.
                driver = "libvulkan_wrapper.so";
                File wrapper = TtrWineRuntime.download(WRAPPER_URL, WRAPPER_SHA256, WRAPPER_SIZE, new File(work, "wrapper.tzst"), "Downloading graphics driver", 99, 99);
                File wrapperTar = new File(work, "wrapper.tar");
                if (TtrZstd.decompress(wrapper.getAbsolutePath(), wrapperTar.getAbsolutePath()) != 0) throw new IOException("graphics driver could not be decompressed");
                TtrTar.extract(wrapperTar, new File(staging, "lib"), name -> {
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    return name.contains("usr/lib/") && base.endsWith(".so") && !base.startsWith("._") ? base : null;
                });
            }
            if (!new File(staging, "lib/" + driver).isFile() || !hasGallium(new File(staging, "lib")))
                throw new IOException("graphics files are incomplete");
            String json = "{\"file_format_version\":\"1.0.0\",\"ICD\":{\"library_path\":\"" + new File(dir, "lib/" + driver).getAbsolutePath() + "\",\"api_version\":\"1.3.289\"}}";
            Files.write(new File(staging, "icd.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.write(new File(staging, MARKER).toPath(), version(c).getBytes(StandardCharsets.US_ASCII));
            TtrWineRuntime.deleteTree(dir);
            Files.move(staging.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            TtrWineRuntime.deleteTree(work); TtrWineRuntime.deleteTree(staging);
        }
    }
    private static boolean hasGallium(File lib) { String[] n = lib.list(); if (n != null) for (String s : n) if (s.startsWith("libgallium-")) return true; return false; }
}
