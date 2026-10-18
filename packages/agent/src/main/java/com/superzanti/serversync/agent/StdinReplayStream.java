package com.superzanti.serversync.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Wraps System.in with a tee: the game still reads every byte in the original
 * order, while a background pump thread watches for the --username argument
 * that Prism feeds in on stdin. Only the username is inspected; no other
 * argument value is stored or logged.
 */
public final class StdinReplayStream extends InputStream {
    private static final int READ_BUFFER = 8192;
    private static final String USERNAME_FLAG = "--username";

    private static StdinReplayStream installed;

    private final InputStream source;
    private final AgentLog log;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private final StringBuilder lineBuffer = new StringBuilder();

    private int chunkOffset;
    private long queuedBytes;
    private boolean eof;
    private boolean closed;
    private boolean expectUsernameLine;
    private String username;

    public StdinReplayStream(InputStream source, AgentLog log) {
        this.source = source;
        this.log = log;
        Thread pump = new Thread(this::pump, "gtnh-sync-stdin-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Replace System.in once. Returns the existing wrapper if it was already
     * installed, so repeated agent attachment never stacks another pump.
     */
    public static synchronized StdinReplayStream install(AgentLog log) {
        if (System.in instanceof StdinReplayStream) {
            installed = (StdinReplayStream) System.in;
            return installed;
        }
        StdinReplayStream stream = new StdinReplayStream(System.in, log);
        System.setIn(stream);
        installed = stream;
        return stream;
    }

    public static synchronized StdinReplayStream current() {
        return installed;
    }

    /**
     * Wait until a username has been parsed or the timeout / EOF arrives.
     * Returns null when no username was seen in time.
     */
    public String awaitUsername(long timeoutMillis) {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        synchronized (this) {
            while (username == null && !eof) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                try {
                    wait(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return username;
        }
    }

    @Override
    public int read() {
        synchronized (this) {
            waitForData();
            if (chunks.isEmpty()) {
                return -1;
            }
            byte[] chunk = chunks.peekFirst();
            int value = chunk[chunkOffset++] & 0xFF;
            queuedBytes--;
            if (chunkOffset >= chunk.length) {
                chunks.removeFirst();
                chunkOffset = 0;
            }
            return value;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        synchronized (this) {
            waitForData();
            if (chunks.isEmpty()) {
                return -1;
            }
            int copied = 0;
            while (copied < length && !chunks.isEmpty()) {
                byte[] chunk = chunks.peekFirst();
                int available = chunk.length - chunkOffset;
                int take = Math.min(length - copied, available);
                System.arraycopy(chunk, chunkOffset, buffer, offset + copied, take);
                copied += take;
                chunkOffset += take;
                queuedBytes -= take;
                if (chunkOffset >= chunk.length) {
                    chunks.removeFirst();
                    chunkOffset = 0;
                }
            }
            return copied;
        }
    }

    @Override
    public int available() {
        synchronized (this) {
            return (int) Math.min(queuedBytes, Integer.MAX_VALUE);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public boolean isClosed() {
        synchronized (this) {
            return closed;
        }
    }

    private void waitForData() {
        while (chunks.isEmpty() && !eof) {
            try {
                wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pump() {
        byte[] buffer = new byte[READ_BUFFER];
        try {
            while (true) {
                int read = source.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                synchronized (this) {
                    chunks.addLast(chunk);
                    queuedBytes += read;
                    parse(chunk, read);
                    notifyAll();
                }
            }
        } catch (Throwable t) {
            warn("stdin replay read failed: " + t);
        }
        synchronized (this) {
            if (lineBuffer.length() > 0) {
                processLine(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
            eof = true;
            notifyAll();
        }
    }

    private void parse(byte[] buffer, int length) {
        int lineStart = 0;
        for (int i = 0; i < length; i++) {
            if (buffer[i] == '\n') {
                lineBuffer.append(new String(buffer, lineStart, i - lineStart, StandardCharsets.ISO_8859_1));
                processLine(lineBuffer.toString());
                lineBuffer.setLength(0);
                lineStart = i + 1;
            }
        }
        if (lineStart < length) {
            lineBuffer.append(new String(buffer, lineStart, length - lineStart, StandardCharsets.ISO_8859_1));
        }
        if (username != null) {
            lineBuffer.setLength(0);
        }
    }

    private void processLine(String rawLine) {
        if (username != null) {
            return;
        }
        String line = rawLine;
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        line = line.trim();
        if (line.isEmpty()) {
            return;
        }
        // "--username" alone on the previous line: this line is its value.
        if (expectUsernameLine && !line.startsWith("--")) {
            expectUsernameLine = false;
            setUsernameIfPresent(firstToken(line), line);
            return;
        }
        int idx = indexOfArgument(line, USERNAME_FLAG);
        if (idx < 0) {
            return;
        }
        String rest = line.substring(idx + USERNAME_FLAG.length());
        if (rest.startsWith("=")) {
            rest = rest.substring(1);
        }
        rest = rest.trim();
        if (rest.isEmpty()) {
            expectUsernameLine = true;
            return;
        }
        expectUsernameLine = false;
        setUsernameIfPresent(firstToken(rest), line);
    }

    /** Index of an argument token on a word boundary, or -1. */
    private static int indexOfArgument(String line, String flag) {
        int from = 0;
        while (true) {
            int idx = line.indexOf(flag, from);
            if (idx < 0) {
                return -1;
            }
            boolean leftBoundary = idx == 0 || Character.isWhitespace(line.charAt(idx - 1));
            int end = idx + flag.length();
            boolean rightBoundary = end >= line.length()
                || line.charAt(end) == '='
                || Character.isWhitespace(line.charAt(end));
            if (leftBoundary && rightBoundary) {
                return idx;
            }
            from = idx + 1;
        }
    }

    private static String firstToken(String value) {
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
            end++;
        }
        return value.substring(0, end).trim();
    }

    private void setUsernameIfPresent(String value, String sourceLine) {
        if (value != null && !value.isEmpty()) {
            noteArgumentNames(sourceLine);
            setUsername(value);
        }
    }

    /**
     * DEBUG：记录命中账号那一行里出现的参数名（只记名字、不记值，避免把令牌写进日志），
     * 用于定位"启动器到底喂了什么进来"。
     */
    private void noteArgumentNames(String line) {
        if (log == null || line == null) {
            return;
        }
        StringBuilder flags = new StringBuilder();
        int count = 0;
        for (String token : line.split("\\s+")) {
            if (count >= 24) {
                break;
            }
            if (token.length() > 1 && token.charAt(0) == '-') {
                int eq = token.indexOf('=');
                flags.append(eq > 0 ? token.substring(0, eq) : token).append(' ');
                count++;
            }
        }
        log.log("DEBUG", "stdin 命中账号，本行参数名=[" + flags.toString().trim() + "] 行长=" + line.length());
    }

    private void setUsername(String value) {
        username = value;
        notifyAll();
    }

    private void warn(String message) {
        if (log != null) {
            log.warn(message);
        }
    }
}
