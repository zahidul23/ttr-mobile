package org.ttrmobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Minimal tar reader for the runtime packages (ustar/GNU: files, directories, symlinks, GNU long
 * names, PAX paths). Every entry must stay inside the target directory; symlinks may not be
 * absolute or climb out of it.
 */
final class TtrTar {
    interface Filter { String map(String name); } // returns the output path, or null to skip

    private TtrTar() { }

    static void extract(File archive, File target, Filter filter) throws IOException {
        Path root = target.getCanonicalFile().toPath();
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive), 1 << 16)) {
            byte[] header = new byte[512];
            String longName = null;
            while (readFully(in, header)) {
                if (empty(header)) break;
                String name = longName != null ? longName : text(header, 0, 100);
                longName = null;
                String prefix = text(header, 345, 155);
                if (!prefix.isEmpty() && header[257] == 'u' && header[263] == '0') name = prefix + "/" + name;
                long size = number(header, 124, 12);
                char type = (char) (header[156] == 0 ? '0' : header[156]);
                if (type == 'L' || type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) Math.min(size, 1 << 20)];
                    if (!readFully(in, data)) throw new EOFException();
                    skip(in, size - data.length + pad(size));
                    if (type == 'L') longName = new String(data, StandardCharsets.UTF_8).replace("\0", "");
                    else if (type == 'x') longName = paxPath(data);
                    continue;
                }
                String mapped = filter == null ? name : filter.map(name);
                if (mapped == null || mapped.isEmpty() || mapped.equals("./") || mapped.equals(".")) { skip(in, size + pad(size)); continue; }
                Path out = root.resolve(mapped).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe path in runtime package: " + name);
                int mode = (int) number(header, 100, 8);
                if (type == '5') { Files.createDirectories(out); skip(in, size + pad(size)); continue; }
                Files.createDirectories(out.getParent());
                if (type == '2') {
                    String link = text(header, 157, 100);
                    if (link.startsWith("/") || !out.getParent().resolve(link).normalize().startsWith(root)) throw new IOException("Unsafe link in runtime package: " + name);
                    Files.deleteIfExists(out); Files.createSymbolicLink(out, Paths.get(link));
                    skip(in, size + pad(size)); continue;
                }
                if (type != '0' && type != '7') { skip(in, size + pad(size)); continue; }
                Files.deleteIfExists(out);
                try (OutputStream file = new FileOutputStream(out.toFile())) { copy(in, file, size); }
                skip(in, pad(size));
                if ((mode & 0100) != 0) out.toFile().setExecutable(true, true);
            }
        }
    }

    private static String paxPath(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) { int i = line.indexOf(" path="); if (i >= 0) return line.substring(i + 6); }
        return null;
    }
    private static boolean empty(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }
    private static long pad(long size) { return (512 - size % 512) % 512; }
    private static String text(byte[] b, int at, int length) {
        int end = at; while (end < at + length && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }
    private static long number(byte[] b, int at, int length) {
        if ((b[at] & 0x80) != 0) { long v = 0; for (int i = at + 1; i < at + length; i++) v = (v << 8) | (b[i] & 0xff); return v; }
        String s = text(b, at, length).trim(); return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }
    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) { if (n == 0) return false; throw new EOFException(); } n += r; }
        return true;
    }
    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) { long s = in.skip(n); if (s <= 0) { if (in.read() < 0) throw new EOFException(); s = 1; } n -= s; }
    }
    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        while (size > 0) { int r = in.read(buffer, 0, (int) Math.min(buffer.length, size)); if (r < 0) throw new EOFException(); out.write(buffer, 0, r); size -= r; }
    }
}
