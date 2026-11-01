package org.ttrmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

/**
 * TTR's glove pointer, read from the game's own phase_3.mf (toonmono.png for the picture, toonmono.cur for the
 * click point). Windows can't load it (Panda only takes a real file at the drive root), so the app draws it.
 */
final class TtrGlove {
    private TtrGlove() { }
    static final class Pointer { final Bitmap image; final int hotX, hotY; Pointer(Bitmap b, int x, int y) { image = b; hotX = x; hotY = y; } }

    /** Returns the glove, or null if the game files aren't there or can't be read. */
    static Pointer load(Context c) {
        try {
            File cache = new File(c.getFilesDir(), "glove-v1.png"), hot = new File(c.getFilesDir(), "glove-v1.hot");
            if (!cache.isFile() || !hot.isFile()) {
                File archive = new File(TtrUpdater.gameDirectory(c), "phase_3.mf");
                byte[] png = subfile(archive, "phase_3/etc/toonmono.png"), cur = subfile(archive, "phase_3/etc/toonmono.cur");
                if (png == null || cur == null || cur.length < 14) return null;
                int x = (cur[10] & 255) | (cur[11] & 255) << 8, y = (cur[12] & 255) | (cur[13] & 255) << 8; // first cursor entry's hotspot
                try (FileOutputStream out = new FileOutputStream(cache)) { out.write(png); }
                try (FileOutputStream out = new FileOutputStream(hot)) { out.write((x + " " + y).getBytes(StandardCharsets.US_ASCII)); }
            }
            Bitmap image = BitmapFactory.decodeFile(cache.getPath());
            String[] spot = new String(java.nio.file.Files.readAllBytes(hot.toPath()), StandardCharsets.US_ASCII).trim().split(" ");
            return image == null ? null : new Pointer(image, Integer.parseInt(spot[0]), Integer.parseInt(spot[1]));
        } catch (Exception e) { return null; }
    }

    /** Reads one entry of a Panda3D multifile (format 1.x; stored or zlib-compressed, not encrypted). */
    static byte[] subfile(File archive, String wanted) throws IOException {
        try (FileChannel f = new FileInputStream(archive).getChannel()) {
            ByteBuffer head = read(f, 0, 18);
            byte[] magic = new byte[6]; head.get(magic);
            if (!new String(magic, StandardCharsets.ISO_8859_1).equals("pmf\0\n\r")) return null;
            int major = head.getShort() & 0xffff, minor = head.getShort() & 0xffff; long scale = head.getInt() & 0xffffffffL;
            boolean timestamps = major > 1 || (major == 1 && minor >= 1);
            long position = timestamps ? 18 : 14;
            for (int guard = 0; guard < 100000 && position > 0 && position < f.size(); guard++) {
                ByteBuffer e = read(f, position, 14 + 4 + 4 + 2);
                long next = e.getInt() & 0xffffffffL, start = e.getInt() & 0xffffffffL, length = e.getInt() & 0xffffffffL; int flags = e.getShort() & 0xffff;
                if ((flags & 16) != 0) { position = next * scale; continue; } // encrypted: not needed here
                long at = position + 14 + ((flags & 8) != 0 ? 4 : 0) + (timestamps ? 4 : 0);
                ByteBuffer n = read(f, at, 2); int nameLength = n.getShort() & 0xffff;
                byte[] name = new byte[nameLength]; read(f, at + 2, nameLength).get(name);
                for (int i = 0; i < nameLength; i++) name[i] = (byte) (255 - (name[i] & 255));
                if ((flags & 1) == 0 && new String(name, StandardCharsets.ISO_8859_1).equals(wanted) && length < (1 << 20)) {
                    byte[] data = new byte[(int) length]; read(f, start * scale, (int) length).get(data);
                    if ((flags & 8) == 0) return data;
                    try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
                        while ((r = in.read(b)) > 0) out.write(b, 0, r); return out.toByteArray();
                    }
                }
                position = next * scale;
            }
            return null;
        }
    }
    private static ByteBuffer read(FileChannel f, long at, int size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) if (f.read(b, at + b.position()) < 0) break;
        b.flip(); return b;
    }
}
