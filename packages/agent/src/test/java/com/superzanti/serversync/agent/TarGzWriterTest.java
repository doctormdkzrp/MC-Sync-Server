package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TarGzWriterTest {

    @Test
    @DisplayName("Writes a readable ustar archive with a long path split into prefix and name")
    void writesUstarEntries(@TempDir Path dir) throws Exception {
        byte[] firstData = "hello".getBytes(StandardCharsets.UTF_8);
        String longName = "journeymap/data/mp/" + "w".repeat(98) + "/DIM0/0,0.png";
        assertEquals(130, longName.length());
        byte[] secondData = new byte[]{1, 2, 3, 4, 5, 6, 7};

        Path target = dir.resolve("out.tar.gz");
        List<TarGzWriter.Entry> entries = Arrays.asList(
            new TarGzWriter.Entry("hello.txt", firstData),
            new TarGzWriter.Entry(longName, secondData));
        TarGzWriter.write(target, entries);

        byte[] raw = gunzip(Files.readAllBytes(target));

        int offset = 0;
        offset = assertEntry(raw, offset, "hello.txt", firstData, false);
        offset = assertEntry(raw, offset, longName, secondData, true);

        assertEquals(1024, raw.length - offset, "expected exactly two zero blocks at EOF");
        for (int i = offset; i < raw.length; i++) {
            assertEquals(0, raw[i], "EOF block must be zero at index " + i);
        }
    }

    @Test
    @DisplayName("A path that cannot fit ustar name plus prefix is rejected")
    void rejectsOverlongPath(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("bad.tar.gz");
        List<TarGzWriter.Entry> entries = Arrays.asList(
            new TarGzWriter.Entry("a".repeat(300), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> TarGzWriter.write(target, entries));
    }

    private static int assertEntry(byte[] raw, int offset, String expectedName, byte[] expectedData, boolean expectPrefix) {
        assertEquals("ustar", new String(raw, offset + 257, 5, StandardCharsets.US_ASCII));
        assertEquals(0, raw[offset + 262]);
        assertEquals('0', raw[offset + 263]);
        assertEquals('0', raw[offset + 264]);
        assertEquals('0', raw[offset + 156]);
        assertEquals(0644L, parseOctal(raw, offset + 100, 8));
        assertEquals(0L, parseOctal(raw, offset + 108, 8));
        assertEquals(0L, parseOctal(raw, offset + 116, 8));
        assertEquals(0L, parseOctal(raw, offset + 329, 8));
        assertEquals(0L, parseOctal(raw, offset + 337, 8));
        assertTrue(parseOctal(raw, offset + 136, 12) > 0L, "mtime should be set");

        long storedChecksum = parseOctal(raw, offset + 148, 8);
        long computedChecksum = 0;
        for (int i = 0; i < 512; i++) {
            byte value = (i >= 148 && i < 156) ? (byte) ' ' : raw[offset + i];
            computedChecksum += value & 0xFF;
        }
        assertEquals(storedChecksum, computedChecksum, "header checksum must match");

        String name = field(raw, offset, 100);
        String prefix = field(raw, offset + 345, 155);
        String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
        assertEquals(expectedName, fullName);

        if (expectPrefix) {
            assertFalse(prefix.isEmpty());
            assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 100);
            assertTrue(prefix.getBytes(StandardCharsets.UTF_8).length <= 155);
            assertEquals(expectedName.substring(prefix.length() + 1), name);
        } else {
            assertEquals("", prefix);
        }

        long size = parseOctal(raw, offset + 124, 12);
        assertEquals(expectedData.length, size);

        int payloadOffset = offset + 512;
        assertArrayEquals(expectedData, Arrays.copyOfRange(raw, payloadOffset, payloadOffset + expectedData.length));

        int next = payloadOffset + expectedData.length;
        int padding = (512 - (expectedData.length % 512)) % 512;
        for (int i = next; i < next + padding; i++) {
            assertEquals(0, raw[i], "payload padding must be zero");
        }
        return next + padding;
    }

    private static long parseOctal(byte[] raw, int offset, int length) {
        String value = new String(raw, offset, length, StandardCharsets.US_ASCII).trim();
        return value.isEmpty() ? 0L : Long.parseLong(value, 8);
    }

    private static String field(byte[] raw, int offset, int length) {
        int end = offset;
        while (end < offset + length && raw[end] != 0) {
            end++;
        }
        return new String(raw, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
