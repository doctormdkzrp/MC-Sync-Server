import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";

import { ServerConfig } from "../config.js";
import { AdminStore } from "./store.js";

export interface McsConfig {
  /** MCSManager 面板地址，如 http://127.0.0.1:23333 */
  baseUrl?: string;
  apiKey?: string;
  daemonId?: string;
  instanceUuid?: string;
  /** 服务端实例的 mods 目录（绝对路径） */
  instanceModsDir?: string;
  /** 服务端实例的 config 目录（绝对路径） */
  instanceConfigDir?: string;
  /** 同步时不允许删的文件名（相对 target 的 basename 白名单） */
  keepFiles?: string[];
}

export interface SyncPlan {
  target: { modsDir: string; configDir: string };
  adds: string[];
  updates: string[];
  removes: string[];
  unchanged: number;
}

function sha256File(p: string): string {
  return crypto.createHash("sha256").update(fs.readFileSync(p)).digest("hex");
}

/** 目录下所有文件（相对路径，POSIX），跳过隐藏项。 */
function listFiles(absDir: string): string[] {
  if (!fs.existsSync(absDir)) return [];
  const out: string[] = [];
  const walk = (rel: string) => {
    const dir = rel ? path.join(absDir, rel) : absDir;
    for (const de of fs.readdirSync(dir, { withFileTypes: true })) {
      if (de.name.startsWith(".")) continue;
      const childRel = rel ? `${rel}/${de.name}` : de.name;
      if (de.isDirectory()) walk(childRel);
      else if (de.isFile()) out.push(childRel);
    }
  };
  walk("");
  return out;
}

/** 哈希缓存：按「路径+大小+时间戳」记住，重复预览不重复算，改了自然失效。 */
const hashCache = new Map<string, string>();

function cachedSha(p: string): string {
  const st = fs.statSync(p);
  const key = `${p}|${st.size}|${Math.floor(st.mtimeMs)}`;
  const hit = hashCache.get(key);
  if (hit) return hit;
  const h = sha256File(p);
  if (hashCache.size > 20000) hashCache.clear();
  hashCache.set(key, h);
  return h;
}

/**
 * 目标是否与来源一致：先比大小，再比内容哈希。
 * 不做「时间戳相近就认了」的捷径——同一毫秒写下的两个不同文件会把捷径骗过去。
 * 「同步」是手动操作，宁慢勿错；重复预览走哈希缓存，不心疼。
 */
function sameFile(src: string, dst: string): boolean {
  const ss = fs.statSync(src);
  if (!fs.existsSync(dst)) return false;
  const ds = fs.statSync(dst);
  if (ss.size !== ds.size) return false;
  return cachedSha(src) === cachedSha(dst);
}

/** 复制并保留源文件的时间戳（下次预览才能走"时间戳一致"的快路径）。 */
function copyKeepTime(src: string, dst: string): void {
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.copyFileSync(src, dst);
  const st = fs.statSync(src);
  try {
    fs.utimesSync(dst, st.atime, st.mtime);
  } catch {
    /* 时间戳设不上不拦事 */
  }
}

function backupDirOf(dataDir: string): string {
  const d = path.join(dataDir, "mcs-backup");
  fs.mkdirSync(d, { recursive: true });
  return d;
}

/** 把被覆盖/被删的服务端实例文件留个底（安全网：同步是单向派生的，随时能从仓库重来）。 */
function backupInstanceFile(dataDir: string, absPath: string): void {
  try {
    const dir = backupDirOf(dataDir);
    const id = `${new Date().toISOString().replace(/[-:T.Z]/g, "").slice(0, 14)}-${crypto.randomBytes(3).toString("hex")}`;
    fs.writeFileSync(
      path.join(dir, `${id}.json`),
      JSON.stringify({ id, target: absPath, size: fs.statSync(absPath).size, savedAt: new Date().toISOString() }, null, 2)
    );
    fs.copyFileSync(absPath, path.join(dir, `${id}.blob`));
  } catch {
    /* 备份失败不拦主流程，但要留痕 */
    console.warn(`[mcs] 备份失败：${absPath}`);
  }
}

export class McInstance {
  constructor(private cfg: ServerConfig, private store: AdminStore) {}

  private get mcs(): McsConfig {
    return ((this.cfg as any).mcs ?? {}) as McsConfig;
  }

  get configured(): boolean {
    const m = this.mcs;
    return !!(m.instanceModsDir || m.instanceConfigDir);
  }

  private modsSrcDir(): string {
    return path.join(this.store.repoRoot, "mods");
  }

  private configSrcDir(): string {
    return path.join(this.store.repoRoot, "config");
  }

  /** 仓库 → 服务端实例的差异（mods 只算「两边都要/服务端也要」，config 全算）。 */
  plan(): SyncPlan {
    const m = this.mcs;
    const targetMods = m.instanceModsDir ?? "";
    const targetConfig = m.instanceConfigDir ?? "";
    const adds: string[] = [];
    const updates: string[] = [];
    const removes: string[] = [];
    let unchanged = 0;

    // ---- mods：来源筛类型 ----
    const srcMods = new Set<string>();
    for (const rel of listFiles(this.modsSrcDir())) {
      const type = this.store.resolveType(rel);
      if (type === "client") continue; // 纯客户端 mod 不进服务端
      srcMods.add(rel);
      if (!targetMods) continue;
      const src = path.join(this.modsSrcDir(), ...rel.split("/"));
      const dst = path.join(targetMods, ...rel.split("/"));
      if (!fs.existsSync(dst)) adds.push(rel);
      else if (!sameFile(src, dst)) updates.push(rel);
      else unchanged++;
    }
    if (targetMods && fs.existsSync(targetMods)) {
      const keep = new Set((m.keepFiles ?? []).map((s) => s.toLowerCase()));
      for (const rel of listFiles(targetMods)) {
        if (rel.includes("/")) continue; // 只看顶层 jar
        if (keep.has(path.basename(rel).toLowerCase())) continue;
        if (!srcMods.has(rel)) removes.push(rel);
      }
    }

    // ---- config：全量 ----
    for (const rel of listFiles(this.configSrcDir())) {
      if (!targetConfig) continue;
      const src = path.join(this.configSrcDir(), ...rel.split("/"));
      const dst = path.join(targetConfig, ...rel.split("/"));
      if (!fs.existsSync(dst)) adds.push(`config/${rel}`);
      else if (!sameFile(src, dst)) updates.push(`config/${rel}`);
      else unchanged++;
    }
    if (targetConfig && fs.existsSync(targetConfig)) {
      for (const rel of listFiles(targetConfig)) {
        if (!fs.existsSync(path.join(this.configSrcDir(), ...rel.split("/")))) removes.push(`config/${rel}`);
      }
    }

    adds.sort();
    updates.sort();
    removes.sort();
    return { target: { modsDir: targetMods, configDir: targetConfig }, adds, updates, removes, unchanged };
  }

  /** 执行同步：复制新增/更新，删掉多余的。被覆盖/删除的实例文件先留底。 */
  apply(): { added: number; updated: number; removed: number } {
    const m = this.mcs;
    const plan = this.plan();
    const mapToTarget = (rel: string): string => {
      if (rel.startsWith("config/")) return path.join(m.instanceConfigDir ?? "", ...rel.split("/").slice(1));
      return path.join(m.instanceModsDir ?? "", ...rel.split("/"));
    };
    const mapToSrc = (rel: string): string => {
      if (rel.startsWith("config/")) return path.join(this.configSrcDir(), ...rel.split("/").slice(1));
      return path.join(this.modsSrcDir(), ...rel.split("/"));
    };

    for (const rel of plan.adds) {
      copyKeepTime(mapToSrc(rel), mapToTarget(rel));
    }
    for (const rel of plan.updates) {
      const dst = mapToTarget(rel);
      if (fs.existsSync(dst)) backupInstanceFile(this.store.dataDir, dst);
      copyKeepTime(mapToSrc(rel), dst);
    }
    for (const rel of plan.removes) {
      const dst = mapToTarget(rel);
      if (fs.existsSync(dst)) backupInstanceFile(this.store.dataDir, dst);
      fs.rmSync(dst, { force: true });
    }
    return { added: plan.adds.length, updated: plan.updates.length, removed: plan.removes.length };
  }

  // ---- MCSManager ----
  private async mcsGet(apiPath: string): Promise<any> {
    const m = this.mcs;
    if (!m.baseUrl || !m.apiKey) throw new Error("未配置 MCSManager（mcs.baseUrl / mcs.apiKey）");
    const url = new URL(apiPath, m.baseUrl);
    url.searchParams.set("apikey", m.apiKey);
    if (m.daemonId) url.searchParams.set("daemonId", m.daemonId);
    if (m.instanceUuid) url.searchParams.set("uuid", m.instanceUuid);
    const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch {
      return { raw: text, status: res.status };
    }
  }

  async status(): Promise<{ running: boolean; raw?: any }> {
    try {
      const data = await this.mcsGet("/api/instance");
      const st = data?.data?.status ?? data?.data?.info?.status;
      return { running: st === 1 || st === 2, raw: data?.data ?? data };
    } catch (e: any) {
      return { running: false, raw: { error: e.message } };
    }
  }

  async restart(): Promise<{ ok: boolean; message: string }> {
    const m = this.mcs;
    if (!m.instanceModsDir && !m.baseUrl) return { ok: false, message: "未配置 MCSManager，跳过重启（改了 mod/config 需手动重启实例才生效）" };
    try {
      const data = await this.mcsGet("/api/protected_instance/restart");
      const ok = data?.status === 200 || data?.status === 0 || !!data?.data;
      return { ok, message: ok ? "已下发重启指令" : `重启返回异常：${JSON.stringify(data).slice(0, 200)}` };
    } catch (e: any) {
      return { ok: false, message: `重启失败：${e.message}` };
    }
  }
}
