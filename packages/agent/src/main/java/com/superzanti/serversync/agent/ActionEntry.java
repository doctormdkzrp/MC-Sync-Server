package com.superzanti.serversync.agent;

/**
 * A single entry of the sync plan. Pure data, no I/O.
 *
 * "path" is where the file lands inside the client instance. "remotePath" is
 * the repository path used in the download URL; it defaults to "path" so
 * authoritative mirror plans keep their old one-path behavior.
 */
public final class ActionEntry {
    public final EAction action;
    public final String path;
    public final String remotePath;
    public final String dir;
    public final String reason;
    public final String sha256;
    public final long size;

    private ActionEntry(EAction action, String path, String remotePath, String dir, String reason,
                        String sha256, long size) {
        this.action = action;
        this.path = path;
        this.remotePath = remotePath == null || remotePath.isEmpty() ? path : remotePath;
        this.dir = dir;
        this.reason = reason;
        this.sha256 = sha256;
        this.size = size;
    }

    public static ActionEntry download(String path, String dir, String reason, String sha256, long size) {
        return download(path, path, dir, reason, sha256, size);
    }

    public static ActionEntry download(String path, String remotePath, String dir, String reason,
                                       String sha256, long size) {
        return new ActionEntry(EAction.DOWNLOAD, path, remotePath, dir, reason, sha256, size);
    }

    public static ActionEntry delete(String path, String dir, String reason) {
        return new ActionEntry(EAction.DELETE, path, path, dir, reason, null, -1);
    }

    public static ActionEntry skip(String path, String reason) {
        return new ActionEntry(EAction.SKIP, path, path, null, reason, null, -1);
    }

    @Override
    public String toString() {
        return action + "(" + path + ", reason=" + reason + ")";
    }
}
