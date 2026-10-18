package com.superzanti.serversync.agent;

/**
 * A directory rule from the manifest "dirs" array.
 */
public class DirRule {
    public String path = "";
    public String repoPath = "";
    public String localPath = "";
    public String tier = "";
    public String owner = "";
    public String mode = "";
    public boolean perAccount = false;
    public String linePrefix = "";

    public boolean isAuthoritativeMirror() {
        return "authoritative".equalsIgnoreCase(tier) && "mirror".equalsIgnoreCase(mode);
    }

    public boolean isUnion() {
        return "union".equalsIgnoreCase(tier);
    }
}
