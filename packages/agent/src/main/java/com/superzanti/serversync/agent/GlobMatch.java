package com.superzanti.serversync.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Self contained glob matcher.
 *
 * Both the pattern and the matched path are normalised to forward slashes first.
 * Supported syntax:
 *   *      any run of characters within a single path segment
 *   **     any run of characters across any number of segments
 *   ?      a single character within a segment
 *   [abc]  a character class (also supports [!abc] / [^abc] negation)
 *
 * This deliberately does not reuse com.superzanti.serversync.util.Glob because
 * that one rewrites patterns for the host file separator and can change
 * semantics on Windows.
 */
public final class GlobMatch {
    private GlobMatch() {
    }

    public static boolean matches(String path, String pattern) {
        if (path == null || pattern == null) {
            return false;
        }
        String normalizedPath = normalize(path);
        String regex = toRegex(normalize(pattern));
        try {
            return Pattern.matches(regex, normalizedPath);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean matchesAny(String path, List<String> patterns) {
        if (patterns == null || path == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String value) {
        return value.replace('\\', '/');
    }

    static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        regex.append('^');
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubled = (i + 1 < n && glob.charAt(i + 1) == '*');
                if (doubled) {
                    i += 2;
                    if (i < n && glob.charAt(i) == '/') {
                        i++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    i++;
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                i++;
                regex.append("[^/]");
            } else if (c == '[') {
                int j = i + 1;
                StringBuilder cls = new StringBuilder("[");
                if (j < n && (glob.charAt(j) == '!' || glob.charAt(j) == '^')) {
                    cls.append('^');
                    j++;
                }
                boolean closed = false;
                while (j < n) {
                    char cc = glob.charAt(j);
                    if (cc == ']') {
                        closed = true;
                        break;
                    }
                    if (cc == '\\') {
                        cls.append("\\\\");
                    } else if (cc == '[' || cc == '^' || cc == ']') {
                        cls.append('\\').append(cc);
                    } else {
                        cls.append(cc);
                    }
                    j++;
                }
                if (closed) {
                    cls.append(']');
                    regex.append(cls);
                    i = j + 1;
                } else {
                    regex.append("\\[");
                    i++;
                }
            } else if (c == '/') {
                regex.append('/');
                i++;
            } else {
                if ("\\.[](){}^$+|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
