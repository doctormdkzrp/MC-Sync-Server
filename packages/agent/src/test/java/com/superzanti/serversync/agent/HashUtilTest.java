package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HashUtilTest {

    @Test
    @DisplayName("Known SHA-256 vectors (raw bytes)")
    void knownVectors() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtil.sha256Hex(new byte[0]));
    }

    @Test
    @DisplayName("File hashing is raw bytes, not text line concatenation")
    void fileRawBytes(@TempDir Path dir) throws Exception {
        Path crlf = dir.resolve("crlf.bin");
        Path lf = dir.resolve("lf.bin");
        Files.write(crlf, "a\r\nb".getBytes(StandardCharsets.UTF_8));
        Files.write(lf, "a\nb".getBytes(StandardCharsets.UTF_8));

        String crlfHash = HashUtil.sha256Hex(crlf);
        String lfHash = HashUtil.sha256Hex(lf);
        assertEquals(64, crlfHash.length());
        // Raw-byte hashing must distinguish CRLF from LF.
        org.junit.jupiter.api.Assertions.assertNotEquals(crlfHash, lfHash);

        // And it must match the byte-array variant for the same content.
        assertEquals(crlfHash, HashUtil.sha256Hex(Files.readAllBytes(crlf)));
    }
}
