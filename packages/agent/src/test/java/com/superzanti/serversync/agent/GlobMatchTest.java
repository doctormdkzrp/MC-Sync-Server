package com.superzanti.serversync.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GlobMatchTest {

    @Test
    @DisplayName("Star matches within a single segment")
    void singleStar() {
        assertTrue(GlobMatch.matches("mods/aaa.jar", "mods/*.jar"));
        assertFalse(GlobMatch.matches("mods/sub/aaa.jar", "mods/*.jar"));
        assertTrue(GlobMatch.matches("wild-ignored-file.test", "wild-ignored*"));
    }

    @Test
    @DisplayName("Double star matches across segments, including zero segments")
    void doubleStar() {
        assertTrue(GlobMatch.matches("foo.disabled", "**/*.disabled"));
        assertTrue(GlobMatch.matches("mods/foo.disabled", "**/*.disabled"));
        assertTrue(GlobMatch.matches("a/b/c/foo.disabled", "**/*.disabled"));
        assertTrue(GlobMatch.matches("anything-deep/nested/x.test", "anything-deep/**"));
        assertTrue(GlobMatch.matches("mods/serversync-1.jar", "**/serversync-*.jar"));
    }

    @Test
    @DisplayName("Question mark matches one character inside a segment")
    void questionMark() {
        assertTrue(GlobMatch.matches("mods/a1.jar", "mods/a?.jar"));
        assertFalse(GlobMatch.matches("mods/a12.jar", "mods/a?.jar"));
        assertFalse(GlobMatch.matches("mods/a/.jar", "mods/a?jar"));
    }

    @Test
    @DisplayName("Character classes match the listed set")
    void characterClasses() {
        assertTrue(GlobMatch.matches("mods/a.txt", "mods/[abc].txt"));
        assertTrue(GlobMatch.matches("mods/c.txt", "mods/[abc].txt"));
        assertFalse(GlobMatch.matches("mods/d.txt", "mods/[abc].txt"));
        assertTrue(GlobMatch.matches("mods/z.txt", "mods/[!abc].txt"));
    }

    @Test
    @DisplayName("Backslashes are normalised to forward slashes")
    void backslashNormalisation() {
        assertTrue(GlobMatch.matches("mods\\sub\\aaa.jar", "mods/**/*.jar"));
        assertTrue(GlobMatch.matches("mods/sub/aaa.jar", "mods\\**\\*.jar"));
    }

    @Test
    @DisplayName("Anchored full match, no accidental prefix matches")
    void anchored() {
        assertFalse(GlobMatch.matches("mods/aaa.jar", "mods/aaa"));
        assertFalse(GlobMatch.matches("prefix-mods/aaa.jar", "mods/*.jar"));
    }

    @Test
    @DisplayName("matchesAny checks the whole list")
    void matchesAnyList() {
        assertTrue(GlobMatch.matchesAny("mods/x.jar.disabled",
            Arrays.asList("**/*.disabled", "mods/*.jar.disabled")));
        assertFalse(GlobMatch.matchesAny("mods/x.jar", Arrays.asList("**/*.disabled", "**/*.part")));
    }
}
