import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import * as zlib from "node:zlib";
import { minimatch } from "minimatch";

import {
  DEFAULT_PROFILE,
  DEFAULT_SNAPSHOT_KEEP,
  PROFILES_DIR,
  PROTOCOL,
  ServerConfig,
  DirRule,
  normPosix,
  resolveToken,
  resolveWithin,
} from "./config.js";

export interface ManifestFile {
  path: string;
  dir: string;
  localPath: string;
  size: number;
  sha256: string;
  tier: string;
  owner: string;
  linePrefix?: string;
}

export interface Manifest {
  protocol: number;
  version: number;
  profile: string;
  generated: string;
  dirs: Array<Record<string, unknown>>;
  files: ManifestFile[];
}

interface UnionRule {
  entry: DirRule;
  relDir: string;
  repoRel: string;
}

export interface SubmitResult {
  ok: boolean;
  version: number;
  profile: string;
  accepted: string[];
  rejected: Array<{ path: string; reason: string }>;
  keyLines: number;
}

function utcNowStr(): string {
  return new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
}

function fileSha256(p: string): string {
  const h = crypto.createHash("sha256");
  const fd = fs.openSync(p, "r");
  try {
    const buf = Buffer.alloc(1024 * 1024);
    let n: number;
    while ((n = fs.readSync(fd, buf, 0, buf.length, null)) > 0) {
      h.update(buf.subarray(0, n));
    }
  } finally {
    fs.closeSync(fd);
  }
  return h.digest("hex");
}

function isExcluded(relPosix: string, patterns: string[] | undefined): boolean {
  for (const p of patterns ?? []) {
    if (minimatch(relPosix, p)) return true;
  }
  return false;
}

/** 取按键行的键名；不是按键行返回 null（认 `:` 与 `=` 两种分隔符）。 */
function keyOf(line: string, prefix: string): string | null {
  if (!line.startsWith(prefix)) return null;
  let cut = -1;
  for (const sep of [":", "="]) {
    const idx = line.indexOf(sep);
    if (idx >= 0 && (cut < 0 || idx < cut)) cut = idx;
  }
  if (cut <= 0) return null;
  return line.slice(0, cut);
}

export class SyncCore {
  readonly cfg: ServerConfig;
  readonly repoRoot: string;
  readonly dirs: DirRule[];
  readonly stateFile: string;
  readonly snapshotDir: string;
  readonly snapshotKeep: number;
  readonly token: string;

  version: number;
  private manifests = new Map<string, Manifest>();
  private manifestPaths = new Map<string, Set<string>>();
  private allPaths = new Set<string>();

  constructor(cfg: ServerConfig) {
    this.cfg = cfg;
    this.repoRoot = path.resolve(cfg.repo_root);
    this.dirs = cfg.dirs ?? [];
    this.stateFile = cfg.state_file ?? path.join(this.repoRoot, "state.json");
    this.snapshotDir =
      cfg.snapshot_dir ?? path.join(path.dirname(this.repoRoot), "snapshots");
    const keep = Number(cfg.snapshot_keep ?? DEFAULT_SNAPSHOT_KEEP);
    this.snapshotKeep = Number.isFinite(keep) ? keep : DEFAULT_SNAPSHOT_KEEP;
    this.token = resolveToken(cfg);

    fs.mkdirSync(path.dirname(this.stateFile), { recursive: true });
    fs.mkdirSync(this.snapshotDir, { recursive: true });
    this.version = this.loadVersion();
  }

  // ---- 状态 / 发号 ----
  private loadVersion(): number {
    try {
      const state = JSON.parse(fs.readFileSync(this.stateFile, "utf-8"));
      const v = Number(state.version ?? 1);
      return v >= 1 ? v : 1;
    } catch {
      return 1;
    }
  }

  private writeState(): void {
    atomicWriteJson(this.stateFile, { version: this.version, generated: utcNowStr() });
  }

  // ---- 规则 -> 仓库路径 ----
  private ruleRepoPath(entry: DirRule, profile: string): string {
    const relDir = normPosix(entry.path);
    if (!entry.perAccount) return relDir;
    if (entry.mode === "line-union") {
      const stem = path.posix.basename(relDir, path.posix.extname(relDir));
      return `${PROFILES_DIR}/${profile}/${stem}.key.txt`;
    }
    return `${PROFILES_DIR}/${profile}/${relDir}`;
  }

  private absRepoPath(repoRel: string): string {
    return repoRel ? path.join(this.repoRoot, ...repoRel.split("/")) : this.repoRoot;
  }

  private unionRules(profile: string): UnionRule[] {
    const out: UnionRule[] = [];
    for (const entry of this.dirs) {
      const relDir = normPosix(entry.path);
      if (!relDir) continue;
      if ((entry.tier ?? "").toLowerCase() !== "union") continue;
      if ((entry.owner ?? "").toLowerCase() === "server") continue;
      out.push({ entry, relDir, repoRel: this.ruleRepoPath(entry, profile) });
    }
    return out;
  }

  // ---- 清单 ----
  getManifest(profile: string = DEFAULT_PROFILE, force = false): Manifest {
    if (force || !this.manifests.has(profile)) {
      const manifest = this.buildManifest(profile);
      this.manifests.set(profile, manifest);
      const paths = new Set(manifest.files.map((f) => f.path));
      this.manifestPaths.set(profile, paths);
      for (const p of paths) this.allPaths.add(p);
    }
    return this.manifests.get(profile)!;
  }

  manifestPathSet(profile: string = DEFAULT_PROFILE): Set<string> {
    if (!this.manifests.has(profile)) this.getManifest(profile);
    return this.manifestPaths.get(profile) ?? new Set();
  }

  anyManifestPaths(): Set<string> {
    if (this.manifests.size === 0) this.getManifest(DEFAULT_PROFILE);
    return new Set(this.allPaths);
  }

  invalidateManifest(): void {
    this.manifests.clear();
    this.manifestPaths.clear();
    this.allPaths.clear();
  }

  private buildManifest(profile: string): Manifest {
    const dirsOut: Array<Record<string, unknown>> = [];
    const filesOut: ManifestFile[] = [];

    for (const entry of this.dirs) {
      const relDir = normPosix(entry.path);
      if (!relDir) continue;
      const mode = (entry.mode ?? "mirror").toLowerCase();
      const repoRel = this.ruleRepoPath(entry, profile);
      const absPath = this.absRepoPath(repoRel);

      const dirOut: Record<string, unknown> = {
        path: relDir,
        repoPath: repoRel,
        localPath: relDir,
        tier: entry.tier ?? "authoritative",
        owner: entry.owner ?? "both",
        mode: entry.mode ?? "mirror",
      };
      if (entry.perAccount) dirOut.perAccount = true;
      if (entry.linePrefix !== undefined) dirOut.linePrefix = entry.linePrefix;

      if (mode === "line-union") {
        dirsOut.push(dirOut);
        if (fs.existsSync(absPath) && fs.statSync(absPath).isFile()) {
          filesOut.push(
            this.fileEntry(repoRel, relDir, absPath, entry, relDir, entry.linePrefix ?? "")
          );
        }
        continue;
      }

      const isUnion = (entry.tier ?? "").toLowerCase() === "union";
      if (!fs.existsSync(absPath)) {
        if (!isUnion) continue;
        dirsOut.push(dirOut);
        continue;
      }

      dirsOut.push(dirOut);
      const excludes = entry.exclude ?? [];
      for (const rel of walkFiles(absPath)) {
        const full = path.join(absPath, rel);
        const repoAbs = path.join(this.repoRoot, ...repoRel.split("/"), ...rel.split("/"));
        const relPosix = path.relative(this.repoRoot, repoAbs).split(path.sep).join("/");
        if (isExcluded(relPosix, excludes)) continue;
        let local = relPosix;
        if (repoRel !== relDir && relPosix.startsWith(repoRel)) {
          local = relDir + relPosix.slice(repoRel.length);
        }
        filesOut.push(this.fileEntry(relPosix, relDir, full, entry, local));
      }
    }

    filesOut.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
    return {
      protocol: PROTOCOL,
      version: this.version,
      profile,
      generated: utcNowStr(),
      dirs: dirsOut,
      files: filesOut,
    };
  }

  private fileEntry(
    rel: string,
    relDir: string,
    full: string,
    entry: DirRule,
    localPath: string,
    linePrefix?: string
  ): ManifestFile {
    const out: ManifestFile = {
      path: rel,
      dir: relDir,
      localPath: localPath || rel,
      size: fs.statSync(full).size,
      sha256: fileSha256(full),
      tier: entry.tier ?? "authoritative",
      owner: entry.owner ?? "both",
    };
    if (linePrefix) out.linePrefix = linePrefix;
    return out;
  }

  // ---- 上交 ----
  private static validateRel(rel: string): string | null {
    if (!rel || rel.includes("\0")) return "invalid-path";
    if (rel.startsWith("/") || rel.startsWith("\\")) return "absolute-path";
    const drive = rel.match(/^[A-Za-z]:/);
    if (drive) return "absolute-path";
    const parts = rel.replace(/\\/g, "/").split("/").filter((p) => p !== "" && p !== ".");
    if (parts.some((p) => p === "..")) return "parent-traversal";
    return null;
  }

  private mapSubmitMember(rel: string, profile: string): { kind: string; dest: string | null; entry: DirRule | null } {
    const norm = normPosix(rel);
    for (const { entry, relDir, repoRel } of this.unionRules(profile)) {
      if ((entry.mode ?? "").toLowerCase() === "line-union") {
        if (norm === relDir) return { kind: "keyline", dest: repoRel, entry };
        continue;
      }
      if (norm === relDir || norm.startsWith(relDir + "/")) {
        const tail = norm.slice(relDir.length).replace(/^\//, "");
        const dest = tail ? repoRel + "/" + tail : repoRel;
        return { kind: "file", dest, entry };
      }
    }
    return { kind: "not-union-tier", dest: null, entry: null };
  }

  private static mergeKeyLines(existingPath: string, payload: Buffer, prefix: string): { lines: number; changed: boolean } {
    const order: string[] = [];
    const current = new Map<string, string>();
    if (fs.existsSync(existingPath)) {
      try {
        for (const raw of fs.readFileSync(existingPath, "utf-8").split(/\r?\n/)) {
          const line = raw.replace(/\r$/, "");
          const key = keyOf(line, prefix);
          if (key === null) continue;
          if (!current.has(key)) order.push(key);
          current.set(key, line);
        }
      } catch {
        return { lines: 0, changed: false };
      }
    }
    for (const raw of payload.toString("utf-8").split(/\r?\n/)) {
      const line = raw.replace(/\r$/, "").trim();
      const key = keyOf(line, prefix);
      if (key === null) continue;
      if (!current.has(key)) order.push(key);
      current.set(key, line);
    }
    if (order.length === 0) return { lines: 0, changed: false };
    const text = order.map((k) => current.get(k)!).join("\n") + "\n";
    const parent = path.dirname(existingPath);
    fs.mkdirSync(parent, { recursive: true });
    const tmp = `${existingPath}.tmp-${process.pid}`;
    fs.writeFileSync(tmp, text, { encoding: "utf-8" });
    fs.renameSync(tmp, existingPath);
    return { lines: order.length, changed: true };
  }

  submit(body: Buffer, profile: string = DEFAULT_PROFILE): SubmitResult {
    const accepted: string[] = [];
    const rejected: Array<{ path: string; reason: string }> = [];
    let keyLines = 0;

    const members = readTarGzSync(body);
    for (const m of members) {
      if (m.isDir) continue;
      const rel = m.name.replace(/\\/g, "/");
      const reason = SyncCore.validateRel(rel);
      if (reason !== null) {
        rejected.push({ path: m.name, reason });
        continue;
      }
      if (!m.isFile) {
        rejected.push({ path: m.name, reason: "not-a-file" });
        continue;
      }
      const { kind, dest, entry } = this.mapSubmitMember(rel, profile);
      if (kind !== "file" && kind !== "keyline") {
        rejected.push({ path: m.name, reason: kind });
        continue;
      }
      const target = resolveWithin(this.repoRoot, dest!);
      if (target === null) {
        rejected.push({ path: m.name, reason: "unsafe-path" });
        continue;
      }
      if (kind === "keyline") {
        const { lines, changed } = SyncCore.mergeKeyLines(
          target,
          m.buffer,
          entry!.linePrefix ?? "key_"
        );
        keyLines += lines;
        if (!changed) rejected.push({ path: m.name, reason: "no-key-lines" });
        else accepted.push(rel);
        continue;
      }
      const parent = path.dirname(target);
      if (parent) fs.mkdirSync(parent, { recursive: true });
      fs.writeFileSync(target, m.buffer);
      accepted.push(rel);
    }

    // 发号 +1，落快照 + 新清单 + state
    this.version += 1;
    const stamp = new Date().toISOString().replace(/[-:.]/g, "").replace("T", "T").slice(0, 15) + "Z";
    const base = `v${this.version}-${stamp}`;
    const snapshotPath = path.join(this.snapshotDir, `${base}.tar.gz`);

    this.invalidateManifest();
    const newManifest = this.getManifest(profile);

    fs.writeFileSync(snapshotPath, body);
    atomicWriteJson(path.join(this.snapshotDir, `${base}.manifest.json`), newManifest);
    this.writeState();
    this.pruneSnapshots();

    return {
      ok: true,
      version: this.version,
      profile,
      accepted,
      rejected,
      keyLines,
    };
  }

  private pruneSnapshots(): void {
    if (this.snapshotKeep < 0) return;
    const snapshots = fs
      .readdirSync(this.snapshotDir)
      .filter((n) => /^v\d+-.*\.tar\.gz$/.test(n))
      .map((n) => {
        const m = n.match(/^v(\d+)-/);
        return { name: n, version: m ? Number(m[1]) : 0 };
      })
      .sort((a, b) => a.version - b.version || (a.name < b.name ? -1 : 1));
    const excess = snapshots.length - Math.max(0, this.snapshotKeep);
    for (const s of snapshots.slice(0, Math.max(0, excess))) {
      const p = path.join(this.snapshotDir, s.name);
      try {
        fs.rmSync(p);
      } catch {}
      const manifestPath = p.slice(0, -".tar.gz".length) + ".manifest.json";
      if (fs.existsSync(manifestPath)) {
        try {
          fs.rmSync(manifestPath);
        } catch {}
      }
    }
  }
}

function atomicWriteJson(p: string, obj: unknown): void {
  const parent = path.dirname(path.resolve(p));
  if (parent) fs.mkdirSync(parent, { recursive: true });
  const tmp = `${p}.tmp-${process.pid}`;
  const fd = fs.openSync(tmp, "w");
  try {
    fs.writeSync(fd, JSON.stringify(obj, null, 2));
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(tmp, p);
}

/** 递归列出目录下所有文件的相对路径（POSIX，跳过隐藏目录与隐藏文件）。 */
function walkFiles(absDir: string): string[] {
  const out: string[] = [];
  const stack: string[] = [""];
  while (stack.length) {
    const rel = stack.pop()!;
    const dir = rel ? path.join(absDir, rel) : absDir;
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const de of entries) {
      if (de.name.startsWith(".")) continue;
      const childRel = rel ? `${rel}/${de.name}` : de.name;
      if (de.isDirectory()) stack.push(childRel);
      else if (de.isFile()) out.push(childRel);
    }
  }
  return out;
}

interface TarMember {
  name: string;
  isDir: boolean;
  isFile: boolean;
  buffer: Buffer;
}

/**
 * 同步解析 tar.gz（body 已在内存里），只依赖 zlib + 手工 ustar 头解析。
 * 兼容 Java 端 TarGzWriter 的输出（ustar、typeflag '0'、长名走 prefix+name 拆分）。
 */
function readTarGzSync(body: Buffer): TarMember[] {
  let tarBytes: Buffer;
  try {
    tarBytes = zlib.gunzipSync(body);
  } catch (e: any) {
    throw new Error("无法解析 tar.gz：" + (e?.message ?? String(e)));
  }
  const members: TarMember[] = [];
  const cstr = (b: Buffer): string => {
    const i = b.indexOf(0);
    return b.toString("utf-8", 0, i < 0 ? b.length : i);
  };
  const BLOCK = 512;
  for (let offset = 0; offset + BLOCK <= tarBytes.length; ) {
    const header = tarBytes.subarray(offset, offset + BLOCK);
    if (header.every((b) => b === 0)) break; // 结束块
    const name = cstr(header.subarray(0, 100));
    const prefix = cstr(header.subarray(345, 500));
    const fullName = prefix ? `${prefix}/${name}` : name;
    const size = parseInt(cstr(header.subarray(124, 136)).trim() || "0", 8) || 0;
    const type = header[156];
    const dataStart = offset + BLOCK;
    const dataLen = Math.ceil(size / BLOCK) * BLOCK;

    const metaOnly = type === 0x4c || type === 0x78 || type === 0x67; // 'L' / 'x' / 'g'
    if (!metaOnly) {
      if (type === 0x35) {
        members.push({ name: fullName, isDir: true, isFile: false, buffer: Buffer.alloc(0) });
      } else {
        const isFile = type === 0x00 || type === 0x30;
        members.push({
          name: fullName,
          isDir: false,
          isFile,
          buffer: isFile ? Buffer.from(tarBytes.subarray(dataStart, dataStart + size)) : Buffer.alloc(0),
        });
      }
    }
    offset = dataStart + dataLen;
  }
  return members;
}
