package com.superzanti.serversync.agent;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a gzip-compressed ustar archive using only the JDK. The agent needs
 * this to upload union files on exit without pulling in an archive library.
 */
public final class TarGzWriter {
    private static final int BLOCK_SIZE = 512;
    private static final int NAME_MAX = 100;
    private static final int PREFIX_MAX = 155;

    public static final class Entry {
        public final String name;
        public final byte[] data;

        public Entry(String name, byte[] data) {
            this.name = name;
            this.data = data == null ? new byte[0] : data;
        }
    }

    private TarGzWriter() {
    }

    public static void write(Path target, List<Entry> entries) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("target path is required");
        }
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream fileOut = Files.newOutputStream(absolute);
             GZIPOutputStream gzip = new GZIPOutputStream(fileOut)) {
            if (entries != null) {
                for (Entry entry : entries) {
                    if (entry != null) {
                        writeEntry(gzip, entry);
                    }
                }
            }
            // tar EOF marker: two zero blocks.
            gzip.write(new byte[BLOCK_SIZE]);
            gzip.write(new byte[BLOCK_SIZE]);
        }
    }

    private static void writeEntry(OutputStream out, Entry entry) throws Exception {
        if (entry.name == null || entry.name.isEmpty()) {
            throw new IllegalArgumentException("entry name is required");
        }
        byte[] nameBytes = entry.name.getBytes(StandardCharsets.UTF_8);
        byte[] data = entry.data;
        byte[] header = new byte[BLOCK_SIZE];

        if (nameBytes.length <= NAME_MAX) {
            copy(nameBytes, 0, header, 0, nameBytes.length);
        } else {
            int[] split = splitPath(nameBytes);
            if (split == null) {
                throw new IllegalArgumentException("entry path too long for ustar: " + entry.name);
            }
            copy(nameBytes, 0, header, 345, split[0]);
            copy(nameBytes, split[1], header, 0, nameBytes.length - split[1]);
        }

        writeOctal(header, 100, 8, 0644L);
        writeOctal(header, 108, 8, 0L);
        writeOctal(header, 116, 8, 0L);
        writeOctal(header, 124, 12, data.length);
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        header[156] = (byte) '0';

        header[257] = (byte) 'u';
        header[258] = (byte) 's';
        header[259] = (byte) 't';
        header[260] = (byte) 'a';
        header[261] = (byte) 'r';
        header[262] = 0;
        header[263] = (byte) '0';
        header[264] = (byte) '0';

        writeOctal(header, 329, 8, 0L);
        writeOctal(header, 337, 8, 0L);

        for (int i = 148; i < 156; i++) {
            header[i] = (byte) ' ';
        }
        long checksum = 0;
        for (byte value : header) {
            checksum += value & 0xFF;
        }
        byte[] checksumBytes = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
        copy(checksumBytes, 0, header, 148, checksumBytes.length);
        header[154] = 0;
        header[155] = (byte) ' ';

        out.write(header);
        out.write(data);
        int padding = (BLOCK_SIZE - (data.length % BLOCK_SIZE)) % BLOCK_SIZE;
        if (padding > 0) {
            out.write(new byte[padding]);
        }
    }

    /**
     * Split a long UTF-8 name into a ustar prefix (directory) and a name field.
     * Returns {prefixLength, nameStart} in bytes, or null when it does not fit.
     */
    private static int[] splitPath(byte[] nameBytes) {
        for (int i = nameBytes.length - 1; i >= 0; i--) {
            if (nameBytes[i] != '/') {
                continue;
            }
            int prefixLength = i;
            int nameLength = nameBytes.length - i - 1;
            if (nameLength > 0 && nameLength <= NAME_MAX && prefixLength <= PREFIX_MAX) {
                return new int[]{prefixLength, i + 1};
            }
        }
        return null;
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String digits = Long.toOctalString(value);
        int digitsLength = length - 1;
        if (digits.length() > digitsLength) {
            throw new IllegalArgumentException("value too large for tar field: " + value);
        }
        int pad = digitsLength - digits.length();
        for (int i = 0; i < digitsLength; i++) {
            header[offset + i] = i < pad ? (byte) '0' : (byte) digits.charAt(i - pad);
        }
        header[offset + length - 1] = 0;
    }

    private static void copy(byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        System.arraycopy(source, sourceOffset, target, targetOffset, length);
    }
}
