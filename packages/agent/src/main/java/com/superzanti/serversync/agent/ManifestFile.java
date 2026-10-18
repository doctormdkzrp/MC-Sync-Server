package com.superzanti.serversync.agent;

/**
 * A single file entry from the manifest "files" array.
 * "path" is POSIX and relative to the repo root, which equals the client instance root.
 */
public class ManifestFile {
    public String path = "";
    public String dir = "";
    public String localPath = "";
    public String linePrefix = "";
    public long size = -1;
    public String sha256 = "";
    public String tier = "";
    public String owner = "";

    public boolean isServerOwned() {
        return "server".equalsIgnoreCase(owner);
    }

    /**
     * Path relative to the client instance. Protocol 2 sends localPath; older
     * servers omit it, in which case path is already instance-relative.
     */
    public String clientPath() {
        if (localPath != null && !localPath.isEmpty()) {
            return localPath;
        }
        return path;
    }
}
