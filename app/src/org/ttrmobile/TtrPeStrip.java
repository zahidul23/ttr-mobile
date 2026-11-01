package org.ttrmobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Removes DWARF debug data from Wine's PE files without changing any loaded byte. */
public final class TtrPeStrip {
    private TtrPeStrip() { }

    /**
     * Removes trailing .debug* sections and the COFF symbol table from one PE file. Returns the
     * bytes saved, or 0 when the file is not a PE image or its layout is not the simple trailing
     * case (it is then left untouched).
     */
    public static long stripDebug(File file) throws IOException {
        long length = file.length();
        if (length < 1024) return 0;
        byte[] head;
        File temp = new File(file.getPath() + ".ttrstrip");
        long end = 0;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            head = new byte[(int) Math.min(length, 65536)]; in.readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getShort(0) != 0x5a4d) return 0;
            int pe = h.getInt(0x3c);
            if (pe <= 0 || pe + 24 > head.length || h.getInt(pe) != 0x00004550) return 0;
            int sections = h.getShort(pe + 6) & 0xffff;
            long symbols = h.getInt(pe + 12) & 0xffffffffL, symbolCount = h.getInt(pe + 16) & 0xffffffffL;
            int optional = pe + 24, optionalSize = h.getShort(pe + 20) & 0xffff, table = optional + optionalSize;
            int magic = h.getShort(optional) & 0xffff;
            if (magic != 0x10b && magic != 0x20b) return 0;
            if (table + sections * 40 > head.length) return 0;
            int alignment = h.getInt(optional + 32);
            int dirCount = h.getInt(optional + (magic == 0x20b ? 108 : 92)), dirs = optional + (magic == 0x20b ? 112 : 96);
            if (alignment <= 0 || dirCount < 0 || dirCount > 16 || dirs + dirCount * 8 > table) return 0;
            boolean[] debug = new boolean[sections];
            int keep = -1;
            for (int i = 0; i < sections; i++) {
                String name = sectionName(in, head, table + i * 40, symbols, symbolCount, length);
                if (name == null) return 0;
                debug[i] = name.startsWith(".debug");
                if (debug[i] && keep < 0) keep = i;
                if (!debug[i] && keep >= 0) return 0; // debug sections must be the trailing entries
            }
            if (keep <= 0) return 0;
            long image = 0;
            for (int i = 0; i < keep; i++) {
                int s = table + i * 40;
                long vsize = h.getInt(s + 8) & 0xffffffffL, va = h.getInt(s + 12) & 0xffffffffL;
                long raw = h.getInt(s + 16) & 0xffffffffL, pointer = h.getInt(s + 20) & 0xffffffffL;
                if (raw > 0) end = Math.max(end, pointer + raw);
                image = Math.max(image, va + (vsize > 0 ? vsize : raw));
            }
            if (end <= table + sections * 40 || end >= length) return 0;
            for (int i = keep; i < sections; i++) {
                int s = table + i * 40;
                long raw = h.getInt(s + 16) & 0xffffffffL, pointer = h.getInt(s + 20) & 0xffffffffL;
                long va = h.getInt(s + 12) & 0xffffffffL, size = Math.max(h.getInt(s + 8) & 0xffffffffL, raw);
                if (raw > 0 && pointer < end) return 0;
                for (int d = 0; d < dirCount; d++) {
                    long rva = h.getInt(dirs + d * 8) & 0xffffffffL;
                    if (d == 4 ? rva != 0 : rva != 0 && rva >= va && rva < va + size) return 0;
                }
            }
            if (symbols != 0 && symbols < end) return 0;
            // Debug directory entries (e.g. CodeView build IDs) must keep their payload.
            if (dirCount > 6) {
                long rva = h.getInt(dirs + 48) & 0xffffffffL, size = h.getInt(dirs + 52) & 0xffffffffL;
                if (rva != 0) {
                    long offset = -1;
                    for (int i = 0; i < keep; i++) {
                        int s = table + i * 40;
                        long va = h.getInt(s + 12) & 0xffffffffL, raw = h.getInt(s + 16) & 0xffffffffL;
                        if (rva >= va && rva + size <= va + raw) offset = rva - va + (h.getInt(s + 20) & 0xffffffffL);
                    }
                    if (offset < 0 || size % 28 != 0 || size > 28 * 64) return 0;
                    byte[] entries = new byte[(int) size]; in.seek(offset); in.readFully(entries);
                    ByteBuffer e = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < size; i += 28) {
                        long payload = e.getInt(i + 16) & 0xffffffffL, pointer = e.getInt(i + 24) & 0xffffffffL;
                        if (pointer != 0 && pointer + payload > end) return 0;
                    }
                }
            }
            image = (image + alignment - 1) / alignment * alignment;
            h.putShort(pe + 6, (short) keep);
            h.putInt(pe + 12, 0); h.putInt(pe + 16, 0);
            h.putInt(optional + 56, (int) image);
            Arrays.fill(head, table + keep * 40, table + sections * 40, (byte) 0);
            try (RandomAccessFile out = new RandomAccessFile(temp, "rw")) {
                out.setLength(0);
                int headerBytes = (int) Math.min(head.length, end);
                out.write(head, 0, headerBytes);
                in.seek(headerBytes);
                byte[] b = new byte[65536]; long left = end - headerBytes;
                while (left > 0) { int n = in.read(b, 0, (int) Math.min(b.length, left)); if (n <= 0) throw new IOException("Short read"); out.write(b, 0, n); left -= n; }
                out.getFD().sync();
            }
        }
        if (temp.length() != end) { temp.delete(); return 0; }
        temp.setExecutable(file.canExecute(), false);
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return length - end;
    }

    /** Short names are inline; long names ("/123") live in the COFF string table. */
    private static String sectionName(RandomAccessFile in, byte[] head, int at, long symbols, long count, long length) throws IOException {
        int n = 0; while (n < 8 && head[at + n] != 0) n++;
        String name = new String(head, at, n, StandardCharsets.ISO_8859_1);
        if (!name.startsWith("/")) return name;
        if (symbols == 0) return null;
        long offset;
        try { offset = Long.parseLong(name.substring(1)); } catch (NumberFormatException e) { return null; }
        long position = symbols + count * 18 + offset;
        if (position < 0 || position >= length) return null;
        byte[] b = new byte[64]; in.seek(position); int read = in.read(b);
        int z = 0; while (z < read && b[z] != 0) z++;
        return new String(b, 0, z, StandardCharsets.ISO_8859_1);
    }
}
