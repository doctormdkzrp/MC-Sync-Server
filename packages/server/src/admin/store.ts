import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";

import { resolveWithin } from "../config.js";

export type ModType = "both" | "server" | "client";

export interface ModMeta {
  name?: string;
  desc?: string;
  type?: ModType;
  uploadedAt?: string;
}

export interface AgentClient {
  machine: string;
  profile?: string;
  agentVersion: string;
  lastSeen: string;
}

export interface AdminSettings {
  recycleKeepDays: number;
  recycleKeepBytes: number;
}

export interface RecycleItem {
  id: string;
  path: string;
  kind: "mod" | "file";
  size: number;
  deletedAt: string;
  deletedBy?: string;
  note?: string;
}

export interface HistoryItem {
  id: string;
  path: string;
  version: number;
  size: number;
  savedAt: string;
  savedBy?: string;
  note?: string;
}

const DEFAULT_SETTINGS: AdminSettings = {
  recycleKeepDays: 30,
  recycleKeepBytes: 2 * 1024 * 1024 * 1024,
};

function nowIso(): string {
  return new Date().toISOString();
}

function newId(): string {
  const t = new Date().toISOString().replace(/[-:T.Z]/g, "").slice(0, 14);
  return `${t}-${crypto.randomBytes(4).toString("hex")}`;
}

function atomicWrite(p: string, data: Buffer | string): void {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  const tmp = `${p}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, data);
  fs.renameSync(tmp, p);
}

function readJson<T>(p: string, fallback: T): T {
  try {
    return JSON.parse(fs.readFileSync(p, "utf-8")) as T;
  } catch {
    return fallback;
  }
}

/**
 * 管理台的数据仓：mod 元数据 / 设置 / 客户端上报，外加回收站与版本历史。
 * 全部落在 dataDir 下，不进客户端清单（客户端永远只看当前最新）。
 */
export class AdminStore {
  readonly dataDir: string;
  readonly repoRoot: string;
  readonly clientOnlyMods: Set<string>;

  private meta: { mods: Record<string, ModMeta>; clients: AgentClient[]; settings: AdminSettings };

  constructor(dataDir: string, repoRoot: string, clientOnlyMods: string[] = []) {
    this.dataDir = dataDir;
    this.repoRoot = path.resolve(repoRoot);
    this.clientOnlyMods = new Set(clientOnlyMods.map((s) => s.toLowerCase()));
    fs.mkdirSync(path.join(dataDir, "recycle"), { recursive: true });
    fs.mkdirSync(path.join(dataDir, "history"), { recursive: true });
    const raw = readJson<any>(path.join(dataDir, "meta.json"), {});
    this.meta = {
      mods: raw.mods ?? {},
      clients: raw.clients ?? [],
      settings: { ...DEFAULT_SETTINGS, ...(raw.settings ?? {}) },
    };
  }

  private saveMeta(): void {
    atomicWrite(path.join(this.dataDir, "meta.json"), JSON.stringify(this.meta, null, 2));
  }

  // ---- mod 元数据 ----
  modsMeta(): Record<string, ModMeta> {
    return this.meta.mods;
  }

  modMeta(file: string): ModMeta | undefined {
    return this.meta.mods[file];
  }

  setModMeta(file: string, patch: Partial<ModMeta>): ModMeta {
    const cur = this.meta.mods[file] ?? {};
    const next: ModMeta = { ...cur, ...patch };
    if (next.uploadedAt === undefined && cur.uploadedAt === undefined) next.uploadedAt = nowIso();
    this.meta.mods[file] = next;
    this.saveMeta();
    return next;
  }

  deleteModMeta(file: string): void {
    delete this.meta.mods[file];
    this.saveMeta();
  }

  /** 类型的最终口径：显式设置 > 预置的纯客户端名单 > 默认两边都要。 */
  resolveType(file: string): ModType {
    const explicit = this.meta.mods[file]?.type;
    if (explicit) return explicit;
    return this.clientOnlyMods.has(file.toLowerCase()) ? "client" : "both";
  }

  // ---- 客户端上报 ----
  clients(): AgentClient[] {
    return this.meta.clients;
  }

  recordClient(machine: string, agentVersion: string, profile?: string): void {
    if (!machine) return;
    const found = this.meta.clients.find((c) => c.machine === machine);
    if (found) {
      found.agentVersion = agentVersion || found.agentVersion;
      if (profile) found.profile = profile;
      found.lastSeen = nowIso();
    } else {
      this.meta.clients.push({ machine, profile, agentVersion: agentVersion || "?", lastSeen: nowIso() });
    }
    this.saveMeta();
  }

  // ---- 设置 ----
  settings(): AdminSettings {
    return this.meta.settings;
  }

  setSettings(patch: Partial<AdminSettings>): AdminSettings {
    if (typeof patch.recycleKeepDays === "number") this.meta.settings.recycleKeepDays = patch.recycleKeepDays;
    if (typeof patch.recycleKeepBytes === "number") this.meta.settings.recycleKeepBytes = patch.recycleKeepBytes;
    this.saveMeta();
    return this.meta.settings;
  }

  // ---- 回收站 ----
  recycleAdd(relPath: string, buf: Buffer, kind: "mod" | "file", note?: string, by?: string): RecycleItem {
    const id = newId();
    const item: RecycleItem = {
      id,
      path: relPath,
      kind,
      size: buf.length,
      deletedAt: nowIso(),
      deletedBy: by,
      note,
    };
    atomicWrite(path.join(this.dataDir, "recycle", `${id}.json`), JSON.stringify(item, null, 2));
    atomicWrite(path.join(this.dataDir, "recycle", `${id}.blob`), buf);
    this.recycleEnforce();
    return item;
  }

  recycleList(): RecycleItem[] {
    const dir = path.join(this.dataDir, "recycle");
    return fs
      .readdirSync(dir)
      .filter((n) => n.endsWith(".json"))
      .map((n) => readJson<RecycleItem | null>(path.join(dir, n), null))
      .filter((x): x is RecycleItem => !!x)
      .sort((a, b) => (a.deletedAt < b.deletedAt ? 1 : -1));
  }

  recycleUsedBytes(): number {
    return this.recycleList().reduce((s, i) => s + i.size, 0);
  }

  recycleGet(id: string): { item: RecycleItem; buf: Buffer } | null {
    if (!/^[0-9A-Za-z-]+$/.test(id)) return null;
    const jp = path.join(this.dataDir, "recycle", `${id}.json`);
    const bp = path.join(this.dataDir, "recycle", `${id}.blob`);
    if (!fs.existsSync(jp) || !fs.existsSync(bp)) return null;
    return { item: readJson<RecycleItem>(jp, null as any), buf: fs.readFileSync(bp) };
  }

  recycleRestore(id: string): { path: string } | null {
    const got = this.recycleGet(id);
    if (!got) return null;
    const target = resolveWithin(this.repoRoot, got.item.path);
    if (target === null) return null;
    if (fs.existsSync(target)) {
      // 目标已存在：先把现有的存档，再覆盖（避免丢东西）
      this.historyAdd(got.item.path, fs.readFileSync(target), "还原前留档");
    }
    fs.mkdirSync(path.dirname(target), { recursive: true });
    atomicWrite(target, got.buf);
    this.recyclePurge(id);
    return { path: got.item.path };
  }

  recyclePurge(id: string): boolean {
    if (!/^[0-9A-Za-z-]+$/.test(id)) return false;
    let ok = false;
    for (const ext of [".json", ".blob"]) {
      const p = path.join(this.dataDir, "recycle", `${id}${ext}`);
      if (fs.existsSync(p)) {
        fs.rmSync(p, { force: true });
        ok = true;
      }
    }
    return ok;
  }

  recyclePurgeAll(): number {
    let n = 0;
    for (const item of this.recycleList()) if (this.recyclePurge(item.id)) n++;
    return n;
  }

  /** 按时间 + 按大小清最老的。 */
  recycleEnforce(): number {
    const { recycleKeepDays, recycleKeepBytes } = this.meta.settings;
    const items = this.recycleList().sort((a, b) => (a.deletedAt < b.deletedAt ? -1 : 1));
    let removed = 0;
    const cutoff = recycleKeepDays > 0 ? Date.now() - recycleKeepDays * 86400_000 : null;
    let bytes = items.reduce((s, i) => s + i.size, 0);
    for (const it of items) {
      const tooOld = cutoff !== null && Date.parse(it.deletedAt) < cutoff;
      const tooBig = recycleKeepBytes > 0 && bytes > recycleKeepBytes;
      if (tooOld || tooBig) {
        if (this.recyclePurge(it.id)) {
          bytes -= it.size;
          removed++;
        }
      }
    }
    return removed;
  }

  // ---- 版本历史 ----
  historyAdd(relPath: string, buf: Buffer, note?: string, by?: string): HistoryItem {
    const version = this.historyList(relPath).length + 1;
    const id = newId();
    const item: HistoryItem = { id, path: relPath, version, size: buf.length, savedAt: nowIso(), savedBy: by, note };
    atomicWrite(path.join(this.dataDir, "history", `${id}.json`), JSON.stringify(item, null, 2));
    atomicWrite(path.join(this.dataDir, "history", `${id}.blob`), buf);
    this.historyEnforce();
    return item;
  }

  private allHistory(): HistoryItem[] {
    const dir = path.join(this.dataDir, "history");
    return fs
      .readdirSync(dir)
      .filter((n) => n.endsWith(".json"))
      .map((n) => readJson<HistoryItem | null>(path.join(dir, n), null))
      .filter((x): x is HistoryItem => !!x);
  }

  historyList(relPath: string): HistoryItem[] {
    return this.allHistory()
      .filter((h) => h.path === relPath)
      .sort((a, b) => b.version - a.version);
  }

  /** 一次扫描出「每个路径有几版历史」，给列表页用（别在循环里逐个查）。 */
  historyCountMap(): Map<string, number> {
    const m = new Map<string, number>();
    for (const h of this.allHistory()) m.set(h.path, (m.get(h.path) ?? 0) + 1);
    return m;
  }

  historyGet(id: string): { item: HistoryItem; buf: Buffer } | null {
    if (!/^[0-9A-Za-z-]+$/.test(id)) return null;
    const jp = path.join(this.dataDir, "history", `${id}.json`);
    const bp = path.join(this.dataDir, "history", `${id}.blob`);
    if (!fs.existsSync(jp) || !fs.existsSync(bp)) return null;
    return { item: readJson<HistoryItem>(jp, null as any), buf: fs.readFileSync(bp) };
  }

  historyRestore(id: string): { path: string } | null {
    const got = this.historyGet(id);
    if (!got) return null;
    const target = resolveWithin(this.repoRoot, got.item.path);
    if (target === null) return null;
    if (fs.existsSync(target)) this.historyAdd(got.item.path, fs.readFileSync(target), "还原前留档");
    fs.mkdirSync(path.dirname(target), { recursive: true });
    atomicWrite(target, got.buf);
    return { path: got.item.path };
  }

  historyEnforce(): number {
    const { recycleKeepDays, recycleKeepBytes } = this.meta.settings;
    const items = this.allHistory().sort((a, b) => (a.savedAt < b.savedAt ? -1 : 1));
    const cutoff = recycleKeepDays > 0 ? Date.now() - recycleKeepDays * 86400_000 : null;
    let bytes = items.reduce((s, i) => s + i.size, 0);
    let removed = 0;
    for (const it of items) {
      const tooOld = cutoff !== null && Date.parse(it.savedAt) < cutoff;
      const tooBig = recycleKeepBytes > 0 && bytes > recycleKeepBytes;
      if (tooOld || tooBig) {
        for (const ext of [".json", ".blob"]) fs.rmSync(path.join(this.dataDir, "history", `${it.id}${ext}`), { force: true });
        bytes -= it.size;
        removed++;
      }
    }
    return removed;
  }

  // ---- 仓库文件操作（改前留档）----
  /** 写仓库文件；若已存在则先把旧内容存进历史。 */
  writeRepoFile(relPath: string, buf: Buffer, note?: string, by?: string): { history: HistoryItem | null } {
    const target = resolveWithin(this.repoRoot, relPath);
    if (target === null) throw new Error("unsafe-path");
    let history: HistoryItem | null = null;
    if (fs.existsSync(target)) history = this.historyAdd(relPath, fs.readFileSync(target), note ?? "覆盖前留档", by);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    atomicWrite(target, buf);
    return { history };
  }

  /** 删仓库文件 → 进回收站。 */
  deleteRepoFile(relPath: string, kind: "mod" | "file", note?: string, by?: string): RecycleItem | null {
    const target = resolveWithin(this.repoRoot, relPath);
    if (target === null) throw new Error("unsafe-path");
    if (!fs.existsSync(target) || !fs.statSync(target).isFile()) return null;
    const item = this.recycleAdd(relPath, fs.readFileSync(target), kind, note, by);
    fs.rmSync(target, { force: true });
    return item;
  }
}
