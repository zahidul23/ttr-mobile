package org.ttrmobile;

import android.app.Activity;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Standalone Windows runtime: Wine (GameNative Proton ARM64EC) with FEX, on our own X server,
 * without any BannerHub/GameHub component. The bootstrap (TtrBootstrap.exe) starts early and
 * waits for the one-use session file written by the launcher, exactly as in the BannerHub build.
 */
public final class TtrRuntime {
    private TtrRuntime() { }
    public static volatile String lastError, lastMessage = "Starting Windows…", snapshotState = "idle";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static TtrActivity activity;
    private static ServiceConnection connection;
    private static volatile boolean bound, preparing, started;
    private static volatile java.lang.Process wine, server;
    private static volatile int retries;

    public static File directory(Context c) throws Exception {
        File dir = new File(c.getNoBackupFilesDir(), "ttr");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create the TTR runtime directory.");
        return dir;
    }
    static File status(Context c) throws Exception { return new File(TtrDiagnostics.windowsDirectory(c), "status.json"); }

    /** Phase reported by the bootstrap (runtime → windows_ready → client_started → game → closed). */
    public static String phase(Context c) {
        if (lastError != null) return "runtime_failed";
        try {
            File status = status(c);
            if (status.isFile()) return new org.json.JSONObject(new String(Files.readAllBytes(status.toPath()), StandardCharsets.UTF_8)).optString("phase", "runtime");
        } catch (Exception ignored) { }
        return "runtime";
    }
    // A status file from an earlier process (e.g. before an update) does not mean a session is open.
    public static boolean clientVisible(Context c) { return started && phase(c).equals("game"); }
    public static boolean isSessionRunning(Context c) {
        if (!started) return false;
        String p = phase(c);
        return p.equals("starting_game") || p.equals("client_started") || p.equals("game");
    }
    public static void resume(Activity a) { if (activity != null) activity.showClient(); }
    public static void resize() { } // the monitor loop forwards size changes through viewport.txt
    private static volatile boolean restartRequested;
    /** True once after Windows was stopped so it restarts with new settings (the launcher screen starts it again). */
    static boolean consumeSetupRetry() { boolean r = restartRequested; restartRequested = false; return r; }
    /** Whether the running Windows session uses the Vulkan graphics path. */
    static volatile boolean vulkanSession;
    /** Stops Windows so it starts again with the current graphics choice. */
    static void restartForGraphics(Context c) { stop(c); restartRequested = true; }
    static void prepareStorage(Activity a) {
        Context c = a.getApplicationContext();
        if (!started) try { Files.write(status(c).toPath(), "{\"phase\":\"runtime\",\"code\":0}".getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        worker.execute(() -> TtrBannerHubCleanup.clean(c));
    }

    public static synchronized void begin(Activity a, Runnable done) {
        activity = (TtrActivity) a;
        // Windows is already up: an earlier error is stale and must not cancel this launch.
        if (started || preparing) { if (started) lastError = null; done.run(); return; }
        preparing = true; lastError = null;
        Context c = a.getApplicationContext(); app = c;
        worker.execute(() -> {
            try {
                File dir = directory(c);
                Files.write(status(c).toPath(), "{\"phase\":\"runtime\",\"code\":0}".getBytes(StandardCharsets.UTF_8));
                if (!TtrWineRuntime.installed(c)) { lastMessage = "Installing Windows…"; TtrWineRuntime.install(c); }
                TtrGraphics.prepare(c);
                TtrStartupProgress.setup = 100; TtrStartupProgress.setupMessage = null;
                prepare(c, dir);
                ui.post(() -> bindDisplay(a, done));
            } catch (Throwable e) {
                preparing = false;
                fail("Windows could not be prepared: " + e.getMessage() + ". Check your connection and storage, then reopen TTR Mobile.");
                TtrDiagnostics.log(c, "Runtime preparation failed: " + e);
                ui.post(done);
            }
        });
    }

    private static void bindDisplay(Activity a, Runnable done) {
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), m -> {
            if (m.what != 2) return true;
            Bundle data = m.getData(); data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
            String error = data.getString("error"); if (error != null) { fail(error); done.run(); return true; }
            ParcelFileDescriptor fd = data.getParcelable("fd");
            if (fd == null) { fail("The display connection is unavailable."); done.run(); return true; }
            if (activity != null) activity.attachDisplay(fd.detachFd());
            Context c = a.getApplicationContext();
            worker.execute(() -> {
                try { startWine(c); started = true; preparing = false; ui.post(done); monitor(c); }
                catch (Throwable e) { preparing = false; fail("Windows could not start: " + e.getMessage()); TtrDiagnostics.log(c, "Wine start failed: " + e); ui.post(done); }
            });
            return true;
        }));
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder binder) {
                bound = true;
                try { Message request = Message.obtain(null, 1); request.replyTo = receiver; new Messenger(binder).send(request); }
                catch (RemoteException e) { fail("The display process is unavailable."); }
            }
            public void onServiceDisconnected(ComponentName n) { bound = false; fail("The display stopped. Reopen TTR Mobile to retry."); }
        };
        if (!a.bindService(new Intent(a, TtrX11Service.class), connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT) /* the display process gets the game's foreground priority, so low memory doesn't kill it first */) { preparing = false; fail("The display could not start."); done.run(); }
    }

    /** Xauthority, keyboard data, library aliases, the bootstrap and its diagnostics path. */
    private static void prepare(Context c, File dir) throws Exception {
        File auth = new File(dir, "Xauthority");
        if (auth.length() <= 64) {
            byte[] token = new byte[16]; new SecureRandom().nextBytes(token);
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(auth))) {
                for (String number : new String[]{"77", "0"}) {
                    out.writeShort(65535); out.writeShort(0); field(out, number.getBytes(StandardCharsets.US_ASCII));
                    field(out, "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII)); field(out, token);
                }
            } finally { Arrays.fill(token, (byte) 0); }
        }
        File marker = new File(dir, "xkb-v1");
        if (!marker.exists()) {
            File root = new File(dir, "xkb"); root.mkdirs();
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(c.getAssets().open("runtime/xkb.zip"))) {
                java.util.zip.ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    File f = new File(root, e.getName()).getCanonicalFile();
                    if (!f.toPath().startsWith(root.getCanonicalFile().toPath())) throw new IOException("Unsafe keyboard data path.");
                    if (e.isDirectory()) { f.mkdirs(); continue; }
                    f.getParentFile().mkdirs(); Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            marker.createNewFile();
        }
        // Only the libraries Wine needs, under the names it requests (never the whole native folder).
        File xlib = new File(dir, "xlib"); xlib.mkdirs();
        String nativeDir = c.getApplicationInfo().nativeLibraryDir;
        String[][] aliases = {{"libX11.so", "libX11.so", "libX11.so.6"}, {"libxcb.so", "libxcb.so", "libxcb.so.1"}, {"libXau.so", "libXau.so", "libXau.so.6"},
            {"libXdmcp.so", "libXdmcp.so", "libXdmcp.so.6"}, {"libXext.so", "libXext.so", "libXext.so.6"}, {"libttrgl4es.so", "libGL.so.1", "libGL.so"},
            {"libXrender.so", "libXrender.so", "libXrender.so.1"}, {"libXfixes.so", "libXfixes.so", "libXfixes.so.3"},
            {"libXrandr.so", "libXrandr.so", "libXrandr.so.2"}, {"libXcursor.so", "libXcursor.so", "libXcursor.so.1"},
            {"libasound.so", "libasound.so", "libasound.so.2"}, {"libandroid-sysvshm.so", "libandroid-sysvshm.so"}};
        for (String[] a : aliases) for (int i = 1; i < a.length; i++) {
            Path link = new File(xlib, a[i]).toPath(), target = Paths.get(nativeDir, a[0]);
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).equals(target)) continue;
            Files.deleteIfExists(link); Files.createSymbolicLink(link, target);
        }
        try (InputStream in = c.getAssets().open("ttr/TtrBootstrap.exe")) { Files.copy(in, new File(dir, "TtrBootstrap.exe").toPath(), StandardCopyOption.REPLACE_EXISTING); }
        Files.write(new File(dir, "diagnostics-path.txt").toPath(), ("Z:" + TtrDiagnostics.windowsDirectory(c).getCanonicalPath().replace('/', '\\') + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void field(DataOutputStream out, byte[] data) throws IOException { out.writeShort(data.length); out.write(data); }

    private static Map<String, String> environment(Context c) throws Exception {
        File dir = directory(c), runtime = TtrWineRuntime.directory(c), loader = TtrWineRuntime.loader(c);
        File home = new File(c.getFilesDir(), "wine-home"), tmp = new File(c.getFilesDir(), "wine-tmp");
        new File(home, ".config").mkdirs(); tmp.mkdirs();
        Map<String, String> env = new HashMap<>();
        env.put("PATH", runtime + "/bin:/system/bin");
        env.put("HOME", home.getAbsolutePath()); env.put("USER", "ttr"); env.put("WINEUSERNAME", "ttr");
        env.put("TMPDIR", tmp.getAbsolutePath()); env.put("XDG_RUNTIME_DIR", tmp.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", home + "/.config"); env.put("XDG_DATA_DIRS", runtime + "/share");
        env.put("LD_LIBRARY_PATH", new File(dir, "xlib") + ":/system/lib64");
        // Developer switch: no_backup/ttr/graphics/lib holds a test OpenGL stack (Mesa zink + Vulkan ICD). Its
        // libraries come first, so its libGL replaces GL4ES; icd.json names the Vulkan driver.
        // Graphics: OpenGL on Vulkan (Mesa zink, see TtrGraphics) when chosen and installed, otherwise GL4ES.
        File graphics = new File(dir, "graphics");
        vulkanSession = TtrGraphics.active(c);
        if (vulkanSession) {
            env.put("LD_LIBRARY_PATH", new File(graphics, "lib") + ":" + new File(dir, "xlib") + ":/system/lib64");
            env.put("LIBGL_DRIVERS_PATH", new File(graphics, "lib/dri").getAbsolutePath());
            env.put("VK_ICD_FILENAMES", new File(graphics, "icd.json").getAbsolutePath());
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink"); env.put("GALLIUM_DRIVER", "zink");
            env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
            if (new File(graphics, "layers").isDirectory()) env.put("VK_ADD_IMPLICIT_LAYER_PATH", new File(graphics, "layers").getAbsolutePath());
        }
        env.put("LD_PRELOAD", c.getApplicationInfo().nativeLibraryDir + "/libttrexec.so"); // child processes via linker64
        env.put("DISPLAY", new File(dir, "tmp/.X11-unix/X77").getAbsolutePath()); env.put("XAUTHORITY", new File(dir, "Xauthority").getAbsolutePath());
        env.put("WINEPREFIX", prefix(c).getAbsolutePath()); env.put("WINELOADER", loader.getAbsolutePath()); env.put("WINELOADERNOEXEC", "1");
        // Detailed logging (Diagnostics menu): process start/exit, exceptions and errors from Wine.
        env.put("WINEDEBUG", detailedLogging(c) ? "err+all,fixme-all,+process,+seh,+loaddll" : "-all");
        try { // Developer switch: no_backup/ttr/winedebug holds a WINEDEBUG value (debuggable builds can write it with run-as).
            File debug = new File(dir, "winedebug");
            if (debug.isFile()) env.put("WINEDEBUG", new String(Files.readAllBytes(debug.toPath()), StandardCharsets.US_ASCII).trim());
        } catch (IOException ignored) { }
        env.put("WINEDLLOVERRIDES", "winepulse.drv,mscoree,mshtml=d"); // no audio driver yet; no .NET/IE prompts
        env.put("PROTON_DISABLE_LSTEAMCLIENT", "1"); env.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        env.put("LC_ALL", "en_US.UTF-8");
        env.put("TTR_AUDIO_PAUSE_FILE", audioPauseFile(c).getAbsolutePath()); // game audio is silent while this exists
        env.put("LIBGL_FB", "3"); env.put("LIBGL_ES", "2"); env.put("LIBGL_NOBANNER", "1"); // GL4ES: desktop GL on Android GLES
        // Full 32-bit textures: GL4ES otherwise uses 16-bit ones on PowerVR, which leaves text edges jagged.
        env.put("LIBGL_AVOID16BITS", "1");
        // No mipmaps (TTR's own mipmapping setting is off too): with them GL4ES drew the laff meter digits and other
        // small text as hard black blocks; without them they render cleanly. Also skips uploading extra texture sizes.
        env.put("LIBGL_MIPMAP", "3");
        // Game surfaces cover the whole X screen, so rotating (a wider window) needs no new surface.
        if (TtrViewport.rootWidth > 0) { env.put("TTR_GL_MIN_WIDTH", String.valueOf(TtrViewport.rootWidth)); env.put("TTR_GL_MIN_HEIGHT", String.valueOf(TtrViewport.renderHeight)); }
        if (new File(dir, "present-off").isFile()) env.put("TTR_PRESENT", "0"); // developer switch: GL4ES's original frame copy
        try { // Developer switch: no_backup/ttr/gl-env holds KEY=VALUE lines for graphics experiments (LIBGL_*, TTR_*).
            File extra = new File(dir, "gl-env");
            if (extra.isFile()) for (String line : new String(Files.readAllBytes(extra.toPath()), StandardCharsets.US_ASCII).split("\n")) {
                int eq = line.indexOf('='); String key = eq > 0 ? line.substring(0, eq).trim() : "";
                if (key.matches("(LIBGL|TTR|MESA|ZINK|VK|GALLIUM|GAMENATIVE|GAMESCOPE|ADRENOTOOLS|FEX|ENABLE|DISABLE|TU|WRAPPER)_[A-Z0-9_]+")) env.put(key, line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) { }
        return env;
    }
    /** Created while the app is in the background; the audio bridge (asound_aaudio.c) then plays silence. */
    static File audioPauseFile(Context c) throws Exception { return new File(directory(c), "audio-paused"); }
    static void setAudioPaused(Context c, boolean paused) {
        try { File f = audioPauseFile(c); if (paused) f.createNewFile(); else f.delete(); } catch (Exception ignored) { }
    }
    /**
     * The runtime keeps its locks in shared memory (wine-tmp/ntsync_userspace*.shm) that outlives its processes. If the
     * app is killed mid-game, a lock can stay held by a process that no longer exists and the next wineserver waits on
     * it forever. With no Windows process left, the file is safe to remove; the runtime creates a fresh one.
     */
    private static void clearStaleLocks(Context c) {
        File[] stale = new File(c.getFilesDir(), "wine-tmp").listFiles((d, n) -> n.startsWith("ntsync_userspace") && n.endsWith(".shm"));
        if (stale != null) for (File f : stale) if (f.delete()) log(c, "Cleared " + f.getName() + " left by an earlier Windows session.");
    }
    static File prefix(Context c) { return new File(c.getFilesDir(), "wine-prefix"); }

    static boolean detailedLogging(Context c) { return c.getSharedPreferences("ttr-debug", 0).getBoolean("detailed", false); }
    static void setDetailedLogging(Context c, boolean on) { c.getSharedPreferences("ttr-debug", 0).edit().putBoolean("detailed", on).commit(); }
    /** Ends Windows processes left over from an earlier start (they keep memory and old state). */
    static int killStrayWindows() {
        int killed = 0, self = android.os.Process.myPid(); File[] all = new File("/proc").listFiles();
        if (all != null) for (File p : all) {
            if (!p.getName().matches("[0-9]+")) continue;
            int pid = Integer.parseInt(p.getName()); if (pid == self) continue;
            try {
                byte[] raw = Files.readAllBytes(new File(p, "cmdline").toPath());
                if (new String(raw, StandardCharsets.UTF_8).startsWith("/system/bin/linker64\u0000")) { android.os.Process.killProcess(pid); killed++; }
            } catch (Exception ignored) { }
        }
        return killed;
    }
    private static boolean interruptIgnored;
    private static java.lang.Process launch(Context c, Map<String, String> env, File log, String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add("/system/bin/linker64"); command.addAll(Arrays.asList(args));
        if (!interruptIgnored) { TtrZstd.ignoreInterrupt(); interruptIgnored = true; }
        ProcessBuilder pb = new ProcessBuilder(command); pb.environment().clear(); pb.environment().putAll(env);
        pb.environment().put("TTR_NEW_SESSION", "1"); // Wine runs in its own session (see ttrexec.c)
        pb.directory(c.getFilesDir()); pb.redirectErrorStream(true); pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        return pb.start();
    }

    /**
     * Wine copies each built-in DLL into the prefix; the copies are byte-identical to the runtime's.
     * Replacing them with links saves roughly 700 MB. Files that differ are left alone.
     */
    private static long shareDlls(File folder, File runtime) {
        long saved = 0; File[] files = folder.listFiles(); if (files == null) return 0;
        byte[] a = new byte[1 << 16], b = new byte[1 << 16];
        for (File f : files) {
            File r = new File(runtime, f.getName());
            try {
                if (Files.isSymbolicLink(f.toPath()) || !f.isFile() || !r.isFile() || f.length() != r.length()) continue;
                boolean same = true;
                try (InputStream x = new FileInputStream(f); InputStream y = new FileInputStream(r)) {
                    int n; while (same && (n = x.read(a)) > 0) { int got = 0; while (got < n) { int m = y.read(b, got, n - got); if (m < 0) { same = false; break; } got += m; } for (int i = 0; same && i < n; i++) same = a[i] == b[i]; }
                }
                if (!same) continue;
                File tmp = new File(folder, f.getName() + ".ttr-link");
                Files.deleteIfExists(tmp.toPath()); Files.createSymbolicLink(tmp.toPath(), r.toPath());
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                saved += r.length();
            } catch (IOException ignored) { }
        }
        return saved;
    }

    private static void startWine(Context c) throws Exception {
        Map<String, String> env = environment(c);
        File dir = directory(c), log = new File(c.getCacheDir(), "wine.log"), loader = TtrWineRuntime.loader(c);
        File wineserver = new File(loader.getParentFile(), "wineserver");
        if (log.length() > 4_000_000) log.delete();
        File configured = new File(prefix(c), "ttr-prefix.v2");
        if (!configured.isFile()) {
            // First run: unpack the runtime's ready-made prefix, then register FEX as the x86-64/x86 emulator.
            lastMessage = "Setting up Windows (one time)…"; TtrStartupProgress.setupMessage = "Setting up Windows (one time)"; TtrStartupProgress.setup = 50;
            File prefix = prefix(c), pack = new File(TtrWineRuntime.directory(c), "prefixPack.txz"), tar = new File(c.getCacheDir(), "prefix.tar");
            if (!pack.isFile()) { TtrWineRuntime.reinstall(c); } // the pack is removed after setup to save space
            TtrWineRuntime.deleteTree(prefix); prefix.mkdirs();
            int x = TtrZstd.unxz(pack.getAbsolutePath(), tar.getAbsolutePath());
            if (x != 0) throw new IOException("The Windows prefix could not be unpacked (" + x + ").");
            TtrTar.extract(tar, prefix, name -> {
                String n = name.startsWith("./") ? name.substring(2) : name;
                if (!n.startsWith(".wine/")) return null;
                n = n.substring(6);
                if (n.startsWith("drive_c/windows/syswow64/")) return null; // TTR is 64-bit: no 32-bit Windows
                return n.startsWith("dosdevices/") ? null : n; // Winlator's absolute drive links are replaced below
            });
            tar.delete();
            File devices = new File(prefix, "dosdevices"); devices.mkdirs();
            Files.createSymbolicLink(new File(devices, "c:").toPath(), Paths.get("../drive_c"));
            Files.createSymbolicLink(new File(devices, "z:").toPath(), Paths.get("/"));
            TtrStartupProgress.setup = 70;
            Map<String, String> setup = new HashMap<>(env); setup.remove("DISPLAY"); setup.remove("XAUTHORITY");
            List<String[]> steps = new ArrayList<>();
            for (String[] k : new String[][]{{"amd64", "libarm64ecfex.dll"}})
                steps.add(new String[]{loader.getAbsolutePath(), "reg", "add", "HKLM\\Software\\Microsoft\\Wow64\\" + k[0], "/ve", "/d", k[1], "/f"});
            steps.add(new String[]{wineserver.getAbsolutePath(), "-w"});
            int step = 0;
            for (String[] command : steps) {
                java.lang.Process p = launch(c, setup, log, command);
                if (!p.waitFor(600, TimeUnit.SECONDS)) { p.destroy(); throw new IOException("Windows setup timed out."); }
                TtrStartupProgress.setup = 70 + 30 * (++step) / steps.size();
            }
            new File(prefix, "drive_c/windows/syswow64").mkdirs();
            configured.createNewFile();
            pack.delete();
            TtrStartupProgress.setup = 100; TtrStartupProgress.setupMessage = null;
        }
        new File(TtrWineRuntime.directory(c), "prefixPack.txz").delete(); // only needed for first-run setup
        for (String old : new String[]{"lib/wine/i386-windows", "lib/wine/i386-windows.off"}) // 32-bit Windows was dropped
            TtrWineRuntime.deleteTree(new File(TtrWineRuntime.directory(c), old));
        File noWow = new File(prefix(c), "ttr-no-wow64.v1");
        if (!noWow.isFile()) { // installs from before 32-bit support was dropped
            File wow = new File(prefix(c), "drive_c/windows/syswow64");
            TtrWineRuntime.deleteTree(wow); wow.mkdirs(); noWow.createNewFile();
        }
        File shared = new File(prefix(c), "ttr-shared-dlls.v1");
        if (!shared.isFile()) {
            TtrStartupProgress.setupMessage = "Optimizing storage (one time)";
            long saved = shareDlls(new File(prefix(c), "drive_c/windows/system32"), new File(TtrWineRuntime.directory(c), "lib/wine/aarch64-windows"))
                + shareDlls(new File(prefix(c), "drive_c/windows/syswow64"), new File(TtrWineRuntime.directory(c), "lib/wine/i386-windows"));
            log(c, "Shared Windows DLLs with the runtime; saved " + saved / 1048576 + " MB.");
            shared.createNewFile(); TtrStartupProgress.setupMessage = null;
        }
        // Apps may not execve files in their data directory: start the server through the linker first.
        // Wine reads the screen size once at startup, so wait until the X screen has the game's size.
        for (int i = 0; i < 50; i++) {
            try (TtrXConnection probe = new TtrXConnection(c)) {
                int[] screen = probe.geometry(0);
                boolean ready = screen[2] == TtrViewport.rootWidth && screen[3] == TtrViewport.renderHeight;
                if (i == 0 || ready)
                    log(c, "X screen " + screen[2] + "x" + screen[3] + ", game size " + TtrViewport.renderWidth + "x" + TtrViewport.renderHeight);
                if (ready) break;
            } catch (Exception ignored) { }
            Thread.sleep(100);
        }
        if (TtrViewport.rootWidth > 0) { env.put("TTR_GL_MIN_WIDTH", String.valueOf(TtrViewport.rootWidth)); env.put("TTR_GL_MIN_HEIGHT", String.valueOf(TtrViewport.renderHeight)); }
        wineScreen = TtrViewport.renderWidth + "x" + TtrViewport.renderHeight;
        int strays = killStrayWindows();
        if (strays > 0) { log(c, "Ended " + strays + " leftover Windows processes."); Thread.sleep(300); }
        clearStaleLocks(c);
        server = launch(c, env, log, wineserver.getAbsolutePath(), "-p", "-f");
        Thread.sleep(300);
        wine = launch(c, env, log, loader.getAbsolutePath(), new File(dir, "TtrBootstrap.exe").getCanonicalPath());
        lastMessage = "Windows starting…";
    }

    /** Restarts the bootstrap if it exits (e.g. after the game closes), so Windows stays warm. */
    private static volatile TtrXConnection x;
    static volatile String wineScreen;
    private static int managedWindow;
    private static long bootstrapStarted;

    /**
     * Minimal window management (there is no X window manager): the visible top-level window is
     * sized to the render area and given keyboard focus. Also restarts the bootstrap if it exits
     * (e.g. after the game closes) so Windows stays warm.
     */
    private static void monitor(Context c) {
        bootstrapStarted = SystemClock.elapsedRealtime();
        while (started) {
            try {
                manageWindow(c);
                java.lang.Process p = wine;
                if (p != null && p.waitFor(250, TimeUnit.MILLISECONDS)) {
                    if (!started) return;
                    // 137 = killed (SIGKILL), e.g. by Android's child-process limit; record it with the server's state.
                    java.lang.Process ws = server;
                    log(c, "Windows helper exited (code " + p.exitValue() + ") after " + (SystemClock.elapsedRealtime() - bootstrapStarted) / 1000
                        + " s; Windows server " + (ws != null && ws.isAlive() ? "running" : "stopped" + (ws != null ? " (code " + ws.exitValue() + ")" : "")) + ".");
                    // Only quick, repeated exits count as failures; the game closing is normal.
                    if (SystemClock.elapsedRealtime() - bootstrapStarted > 30000) retries = 0;
                    if (++retries > 5) { fail("Windows stopped repeatedly. Reopen TTR Mobile."); return; }
                    TtrSession.clear(c);
                    // Start Windows cleanly: end what is left of the old instance, then the server and the helper.
                    java.lang.Process oldServer = server; if (oldServer != null) oldServer.destroy();
                    int strays = killStrayWindows(); if (strays > 0) log(c, "Ended " + strays + " leftover Windows processes.");
                    if (strays > 0) Thread.sleep(300);
                    clearStaleLocks(c);
                    Map<String, String> fresh = environment(c);
                    if (TtrViewport.rootWidth > 0) { fresh.put("TTR_GL_MIN_WIDTH", String.valueOf(TtrViewport.rootWidth)); fresh.put("TTR_GL_MIN_HEIGHT", String.valueOf(TtrViewport.renderHeight)); }
                    server = launch(c, fresh, new File(c.getCacheDir(), "wine.log"), new File(TtrWineRuntime.loader(c).getParentFile(), "wineserver").getAbsolutePath(), "-p", "-f");
                    Thread.sleep(300);
                    wine = launch(c, fresh, new File(c.getCacheDir(), "wine.log"), TtrWineRuntime.loader(c).getAbsolutePath(), new File(directory(c), "TtrBootstrap.exe").getCanonicalPath());
                    bootstrapStarted = SystemClock.elapsedRealtime();
                }
            } catch (Exception e) { log(c, "Monitor: " + e); return; }
        }
    }

    private static String viewportWritten;
    /** Cursor mode shows the X pointer (TTR's own cursor); tap mode hides it. */
    static volatile boolean cursorVisible;
    private static long cursorLogged; private static String lastCursorImage;

    /**
     * The bootstrap sizes the game window from viewport.txt (Win32 resize, so the game re-renders at
     * the new size). Here we keep that file current and give the game keyboard focus (no window manager).
     */
    private static void manageWindow(Context c) {
        try {
            String size = TtrViewport.renderWidth + " " + TtrViewport.renderHeight;
            if (!size.equals(viewportWritten)) {
                android.util.AtomicFile file = new android.util.AtomicFile(new File(TtrDiagnostics.windowsDirectory(c), "viewport.txt"));
                java.io.FileOutputStream out = file.startWrite();
                try { out.write((size + "\n").getBytes(StandardCharsets.US_ASCII)); file.finishWrite(out); } catch (IOException e) { file.failWrite(out); throw e; }
                viewportWritten = size;
            }
            if (!phase(c).equals("game")) { managedWindow = 0; return; }
            if (x == null) x = new TtrXConnection(c);
            x.setCursorVisible(cursorVisible);
            if (detailedLogging(c) && SystemClock.elapsedRealtime() - cursorLogged > 3000) { cursorLogged = SystemClock.elapsedRealtime(); String image = x.cursorImage(); if (!image.equals(lastCursorImage)) { lastCursorImage = image; log(c, image); } }
            int window = x.visibleWindow();
            if (window != 0 && window != managedWindow) { x.focus(window); managedWindow = window; }
            // Without a window manager nothing restores focus once Wine drops it (resize, rotation), so keys
            // (movement, buttons) would stop reaching the game. Give it back whenever nothing has focus.
            else if (window != 0) { int f = x.focused(); if (f == 0 || f == 1 || f == x.root || (f != window && x.topLevel.contains(f))) x.focus(window); }
        } catch (Exception e) { try { if (x != null) x.close(); } catch (IOException ignored) { } x = null; }
    }

    public static synchronized void stop(Context c) {
        started = false; preparing = false;
        java.lang.Process w = wine, s = server; wine = null; server = null;
        if (w != null) w.destroy();
        try { launch(c, environment(c), new File(c.getCacheDir(), "wine.log"), new File(TtrWineRuntime.loader(c).getParentFile(), "wineserver").getAbsolutePath(), "-k"); } catch (Exception ignored) { }
        if (s != null) s.destroy();
        if (connection != null && bound) { try { c.unbindService(connection); } catch (Exception ignored) { } bound = false; }
        activity = null;
    }

    private static volatile Context app;
    static void fail(String message) { lastError = message; lastMessage = "Windows needs attention."; Context c = app; if (c != null) TtrDiagnostics.log(c, "Runtime: " + message); }
    static void log(Context c, String s) { TtrDiagnostics.log(c, s); }
}
