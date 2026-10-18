package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class StdinReplayStreamTest {

    private static StdinReplayStream open(String text) {
        return new StdinReplayStream(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), null);
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[7];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("Single-line --username=NAME form is recognized")
    void recognizesInlineUsername() {
        StdinReplayStream stream = open("--accessToken secret\n--username=Fgod\n--uuid x\n");
        assertEquals("Fgod", stream.awaitUsername(2000));
    }

    @Test
    @DisplayName("Two-line --username / NAME form is recognized")
    void recognizesTwoLineUsername() {
        StdinReplayStream stream = open("--username\n\nFgod\n--accessToken secret\n");
        assertEquals("Fgod", stream.awaitUsername(2000));
    }

    @Test
    @DisplayName("Game-side reads see every byte in the original order")
    void replaysAllBytesExactly() throws Exception {
        String text = "--username\r\nFgod\r\n--accessToken a b c\r\nxyz\r\n";
        byte[] expected = text.getBytes(StandardCharsets.ISO_8859_1);
        StdinReplayStream stream = open(text);
        assertArrayEquals(expected, drain(stream));
    }

    @Test
    @DisplayName("No username yields null after the timeout")
    void noUsernameTimesOut() {
        StdinReplayStream stream = open("--accessToken secret\n");
        assertNull(stream.awaitUsername(300));
    }

    @Test
    @DisplayName("Read returns -1 once the pump reaches EOF")
    void eofReturnsMinusOne() throws Exception {
        StdinReplayStream stream = open("abc\n");
        drain(stream);
        assertEquals(-1, stream.read());
    }

    @Test
    @DisplayName("Space separated --username NAME on a shared line is recognized")
    void recognizesSpaceSeparatedUsername() {
        StdinReplayStream stream = open("--accessToken t --username Fgod --uuid u\n");
        assertEquals("Fgod", stream.awaitUsername(2000));
    }

    @Test
    @DisplayName("--userProperties style arguments never shadow the real username")
    void ignoresUnrelatedArguments() {
        StdinReplayStream stream = open("--userProperties {\"x\":1}\n--username=Fgod\n");
        assertEquals("Fgod", stream.awaitUsername(2000));
    }

    @Test
    @DisplayName("Username with a Windows CR line ending is recognized")
    void recognizesCarriageReturn() {
        StdinReplayStream stream = open("--username\r\nFgod\r\n");
        assertEquals("Fgod", stream.awaitUsername(2000));
    }
}
