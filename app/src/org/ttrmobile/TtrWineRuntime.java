package org.ttrmobile;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Installs the Windows runtime from public, pinned sources (no BannerHub/GameHub servers):
 *  - Wine: GameNative's open-source Proton 11 ARM64EC build for Android (GitHub releases).
 *  - FEX: the official FEX x86/x86-64 emulator DLLs for Wine (FEX's Ubuntu PPA package, bundled in the APK).
 * Both are verified by SHA-256, unpacked into a staging folder, debug data is removed,
 * and the folder is renamed into place only when complete.
 */
final class TtrWineRuntime {
    private TtrWineRuntime() { }

    static final String WINE_URL = "https://github.com/GameNative/proton-wine/releases/download/proton-11.0-2-20261024/proton-11.0-2-arm64ec.wcp";
    static final String WINE_SHA256 = "58d0eec540e01f24a4f7969432febe8250e49416d37c5c174ebec66b1baf1864";
    static final long WINE_SIZE = 98222999L;
    /** FEX ships in the APK (native/fex): its PPA drops old builds, so a pinned download stops working. */
    static final String FEX_ASSET = "runtime/fex.deb";
    static final String FEX_SHA256 = "edd6a8266f82199b1323549d64d75295b44773330dc61a44e3e5d9529bd100ba";
    static final long FEX_SIZE = 1827696L;
    static final String VERSION = WINE_SHA256.substring(0, 16) + "-" + FEX_SHA256.substring(0, 16) + "-v2";

    static File directory(Context c) { return new File(c.getFilesDir(), "wine-runtime"); }
    static File loader(Context c) { return new File(directory(c), "bin/wine"); }

    static boolean installed(Context c) {
        File marker = new File(directory(c), ".ttr-runtime");
        try { return marker.isFile() && VERSION.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII).trim()); }
        catch (IOException e) { return false; }
    }

    /** Blocking; publishes progress through TtrStartupProgress. */
    static synchronized void install(Context c) throws Exception {
        if (installed(c)) return;
        File downloads = new File(c.getCacheDir(), "wine-download"); downloads.mkdirs();
        File staging = new File(c.getFilesDir(), "wine-runtime.partial");
        try {
            File wcp = download(WINE_URL, WINE_SHA256, WINE_SIZE, new File(downloads, "proton.wcp"), "Downloading Windows (Wine)", 0, 60);
            File deb = bundled(c, FEX_ASSET, FEX_SHA256, FEX_SIZE, new File(downloads, "fex.deb"), "Adding x86 emulator (FEX)", 60);

            stage("Unpacking Windows", 62);
            File tar = new File(downloads, "proton.tar");
            int z = TtrZstd.decompress(wcp.getAbsolutePath(), tar.getAbsolutePath());
            if (z != 0) throw new IOException("Runtime package could not be decompressed (" + z + ").");
            deleteTree(staging); staging.mkdirs();
            // Keep the ready-made prefix pack: this build expects it (wineboot alone leaves drive_c empty).
            // TTR and our bootstrap are 64-bit, so the 32-bit (WoW64) half of Windows is left out (~140 MB).
            TtrTar.extract(tar, staging, name -> name.equals("profile.json") || name.contains("lib/wine/i386-windows/") ? null : name);
            tar.delete();

            stage("Adding x86 emulator", 85);
            File fexTar = new File(downloads, "fex.tar"), fexData = new File(downloads, "fex-data.tar.zst");
            debMember(deb, "data.tar.zst", fexData);
            z = TtrZstd.decompress(fexData.getAbsolutePath(), fexTar.getAbsolutePath());
            if (z != 0) throw new IOException("Emulator package could not be decompressed (" + z + ").");
            TtrTar.extract(fexTar, new File(staging, "lib/wine/aarch64-windows"), name -> {
                String base = name.substring(name.lastIndexOf('/') + 1);
                return name.contains("/aarch64-windows/") && base.equals("libarm64ecfex.dll") ? base : null;
            });
            fexTar.delete(); fexData.delete();
            if (!new File(staging, "lib/wine/aarch64-windows/libarm64ecfex.dll").isFile()) throw new IOException("The x86-64 emulator is missing from its package.");

            stage("Optimizing storage", 88);
            File[] folders = {new File(staging, "lib/wine/aarch64-windows")};
            int total = 0, done = 0;
            for (File f : folders) { String[] n = f.list(); if (n != null) total += n.length; }
            for (File f : folders) {
                File[] files = f.listFiles(); if (files == null) continue;
                for (File file : files) {
                    if (file.isFile() && !Files.isSymbolicLink(file.toPath())) TtrPeStrip.stripDebug(file);
                    TtrStartupProgress.setup = 88 + 11 * (++done) / Math.max(1, total);
                }
            }
            Files.write(new File(staging, ".ttr-runtime").toPath(), VERSION.getBytes(StandardCharsets.US_ASCII));
            File target = directory(c);
            deleteTree(target);
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            wcp.delete(); deb.delete();
            TtrStartupProgress.setup = 100;
        } finally {
            TtrStartupProgress.setupBytes = TtrStartupProgress.setupTotalBytes = -1; TtrStartupProgress.setupSpeed = null;
        }
    }

    /** Downloads and unpacks the runtime again (used when its one-time prefix pack is needed again). */
    static synchronized void reinstall(Context c) throws Exception {
        Files.deleteIfExists(new File(directory(c), ".ttr-runtime").toPath());
        install(c);
    }

    private static void stage(String message, int percent) {
        TtrStartupProgress.setupMessage = message; TtrStartupProgress.setup = percent;
        TtrStartupProgress.setupBytes = TtrStartupProgress.setupTotalBytes = -1; TtrStartupProgress.setupSpeed = null;
    }

    /** Copies a file shipped in the APK's assets, verified against the pinned SHA-256. */
    static File bundled(Context c, String asset, String sha256, long size, File out, String message, int percent) throws Exception {
        stage(message, percent);
        try (InputStream in = c.getAssets().open(asset); OutputStream file = new FileOutputStream(out)) {
            byte[] buffer = new byte[1 << 16]; int n;
            while ((n = in.read(buffer)) > 0) file.write(buffer, 0, n);
        }
        if (out.length() != size || !sha256.equals(hash(out))) { out.delete(); throw new IOException("The x86 emulator in the app failed verification; reinstall TTR Mobile."); }
        return out;
    }

    /** Resumable HTTPS download, verified against the pinned SHA-256; reuses a verified file. */
    static File download(String url, String sha256, long size, File out, String message, int from, int to) throws Exception {
        TtrStartupProgress.setupMessage = message; TtrStartupProgress.setup = from;
        if (out.length() == size && sha256.equals(hash(out))) return out;
        if (out.length() > size) out.delete();
        long have = out.length(), started = System.nanoTime(), startBytes = have;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(true);
        if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
        int code = connection.getResponseCode();
        if (code == 200 && have > 0) { have = 0; startBytes = 0; }
        else if (code != 200 && code != 206) throw new IOException("Download failed (HTTP " + code + ").");
        try (InputStream in = connection.getInputStream(); OutputStream file = new FileOutputStream(out, have > 0)) {
            byte[] buffer = new byte[1 << 16]; int n; long got = have, last = 0;
            while ((n = in.read(buffer)) > 0) {
                file.write(buffer, 0, n); got += n;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                long now = System.nanoTime();
                if (now - last > 250_000_000L) {
                    last = now;
                    double seconds = Math.max(0.001, (now - started) / 1e9);
                    TtrStartupProgress.setupBytes = got; TtrStartupProgress.setupTotalBytes = size;
                    TtrStartupProgress.setupSpeed = String.format(Locale.US, "%.1f MB/s", (got - startBytes) / seconds / 1e6);
                    TtrStartupProgress.setup = from + (int) ((to - from) * got / Math.max(1, size));
                }
            }
        } finally { connection.disconnect(); }
        if (out.length() != size || !sha256.equals(hash(out))) { out.delete(); throw new IOException("Downloaded runtime failed verification; it will be fetched again."); }
        return out;
    }

    static String hash(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte x : d.digest()) s.append(String.format("%02x", x)); return s.toString();
    }

    /** Copies one member out of a Debian package (ar archive). */
    private static void debMember(File deb, String name, File out) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(deb)))) {
            byte[] magic = new byte[8]; in.readFully(magic);
            if (!"!<arch>\n".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("Invalid emulator package.");
            byte[] header = new byte[60];
            while (true) {
                try { in.readFully(header); } catch (EOFException e) { break; }
                String member = new String(header, 0, 16, StandardCharsets.US_ASCII).trim();
                if (member.endsWith("/")) member = member.substring(0, member.length() - 1);
                long size = Long.parseLong(new String(header, 48, 10, StandardCharsets.US_ASCII).trim());
                if (member.equals(name)) {
                    try (OutputStream o = new FileOutputStream(out)) { byte[] b = new byte[1 << 16]; long left = size; while (left > 0) { int r = in.read(b, 0, (int) Math.min(b.length, left)); if (r < 0) throw new EOFException(); o.write(b, 0, r); left -= r; } }
                    return;
                }
                long skip = size + (size & 1); while (skip > 0) skip -= in.skip(skip);
            }
        }
        throw new IOException("The emulator package is missing " + name + ".");
    }

    static void deleteTree(File f) throws IOException {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) { File[] k = f.listFiles(); if (k != null) for (File x : k) deleteTree(x); }
        Files.deleteIfExists(f.toPath());
    }
}
