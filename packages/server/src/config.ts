import * as fs from "node:fs";
import * as path from "node:path";

/** 一条目录规则（与 config.json 的 dirs[] 一一对应）。 */
export interface DirRule {
  path: string;
  tier: "authoritative" | "union";
  owner: "both" | "client" | "server";
  mode: "mirror" | "union" | "line-union";
  perAccount?: boolean;
  linePrefix?: string;
  exclude?: string[];
  /** 管理台可见的额外元数据（可选，v0.3 起） */
  localPath?: string;
}

export interface ServerConfig {
  repo_root: string;
  dirs: DirRule[];
  state_file?: string;
  snapshot_dir?: string;
  snapshot_keep?: number;
  listen?: { host?: string; port?: number };
  submit_token?: string;
  submit_token_file?: string;
  log_file?: string;
  /** 管理台配置（v0.3） */
  admin?: {
    enabled?: boolean;
    token?: string;
    token_file?: string;
    recycle_keep_days?: number;
    recycle_keep_bytes?: number;
  };
}

export const PROTOCOL = 2;
export const DEFAULT_SNAPSHOT_KEEP = 20;
export const MAX_SUBMIT_BYTES = 1024 * 1024 * 1024; // 1 GiB
export const READ_CHUNK = 1024 * 1024;
export const DEFAULT_PROFILE = "default";
export const PROFILES_DIR = "profiles";
const PROFILE_RE = /^[A-Za-z0-9_-]{1,32}$/;

export function loadConfig(configPath: string): ServerConfig {
  const raw = fs.readFileSync(configPath, "utf-8");
  const cfg = JSON.parse(raw) as ServerConfig;
  if (cfg === null || typeof cfg !== "object" || Array.isArray(cfg)) {
    throw new Error("配置文件顶层必须是一个 JSON 对象");
  }
  return cfg;
}

export function resolveToken(cfg: ServerConfig): string {
  if (cfg.submit_token_file) {
    return fs.readFileSync(cfg.submit_token_file, "utf-8").split(/\r?\n/)[0].trim();
  }
  return cfg.submit_token ?? "";
}

export function normalizeProfile(raw: unknown): string {
  if (raw === undefined || raw === null) return DEFAULT_PROFILE;
  const value = String(raw).trim();
  if (value === "") return DEFAULT_PROFILE;
  if (!PROFILE_RE.test(value)) throw new Error("invalid profile name");
  return value;
}

/** 把路径规整成 POSIX 形式、去掉首尾斜杠与单点段。 */
export function normPosix(value: string): string {
  return (value || "")
    .replace(/\\/g, "/")
    .split("/")
    .filter((p) => p !== "" && p !== ".")
    .join("/");
}

/** 非严格 realpath：叶子不存在时逐级向上解析，容忍不存在的目标路径。 */
function realpathLenient(p: string): string {
  try {
    return fs.realpathSync(p);
  } catch (e: any) {
    if (e && e.code === "ENOENT") {
      const parent = path.dirname(p);
      if (parent === p) return p;
      return path.join(realpathLenient(parent), path.basename(p));
    }
    throw e;
  }
}

/** 把相对路径解析到 root 之内；越界返回 null。 */
export function resolveWithin(root: string, rel: string): string | null {
  const rootReal = realpathLenient(root);
  const candidate = realpathLenient(path.join(rootReal, rel));
  const relPath = path.relative(rootReal, candidate);
  if (relPath === "" || (relPath !== ".." && !relPath.startsWith(`..${path.sep}`) && !path.isAbsolute(relPath))) {
    return candidate;
  }
  return null;
}
