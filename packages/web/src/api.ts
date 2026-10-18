// 统一接口封装：fetch + credentials:'include' + 统一错误处理。
// 路径/字段严格按 docs/ADMIN_API.md。
import type {
  AgentInfo,
  AgentLatest,
  ConfigFile,
  ConfigTreeEntry,
  DirEntry,
  HistoryItem,
  InstanceStatus,
  ModItem,
  Overview,
  RecycleItem,
  RecycleSettings,
  SessionResp,
  SyncPreview,
  TimeLike,
  TreeEntry,
} from './types'

export const API_BASE = '/api'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

let unauthorizedHandler: (() => void) | null = null
/** 收到 401 时回调（router 里注册：清会话状态并跳登录页） */
export function setUnauthorizedHandler(fn: () => void): void {
  unauthorizedHandler = fn
}

async function parseResponse<T>(res: Response): Promise<T> {
  if (res.status === 401) {
    if (unauthorizedHandler) unauthorizedHandler()
    throw new ApiError(401, '未登录或登录已过期，请重新登录')
  }
  const ctype = res.headers.get('content-type') || ''
  if (!res.ok) {
    let msg = `请求失败（HTTP ${res.status}）`
    if (ctype.includes('application/json')) {
      try {
        const data: unknown = await res.json()
        if (data && typeof data === 'object' && 'error' in data) {
          const e = (data as { error?: unknown }).error
          if (typeof e === 'string' && e) msg = e
        }
      } catch {
        /* 保底用 HTTP 状态文案 */
      }
    }
    throw new ApiError(res.status, msg)
  }
  if (ctype.includes('application/json')) {
    return (await res.json()) as T
  }
  return (await res.text()) as unknown as T
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const opts: RequestInit = { credentials: 'include', ...init }
  if (opts.body != null && !(opts.body instanceof FormData)) {
    opts.headers = { 'Content-Type': 'application/json', ...(init.headers || {}) }
  }
  let res: Response
  try {
    res = await fetch(API_BASE + path, opts)
  } catch {
    throw new ApiError(0, '无法连接服务器（网络错误）')
  }
  return parseResponse<T>(res)
}

const get = <T>(p: string): Promise<T> => request<T>(p)
const post = <T>(p: string, body?: unknown): Promise<T> =>
  request<T>(p, body === undefined ? { method: 'POST' } : { method: 'POST', body: JSON.stringify(body) })
const put = <T>(p: string, body: unknown): Promise<T> => request<T>(p, { method: 'PUT', body: JSON.stringify(body) })
const patch = <T>(p: string, body: unknown): Promise<T> => request<T>(p, { method: 'PATCH', body: JSON.stringify(body) })
const del = <T>(p: string): Promise<T> => request<T>(p, { method: 'DELETE' })
const upload = <T>(p: string, fd: FormData): Promise<T> => request<T>(p, { method: 'POST', body: fd })

const q = (s: string): string => encodeURIComponent(s)
const qs = (params: Record<string, string | undefined>): string => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v) p.set(k, v)
  const s = p.toString()
  return s ? `?${s}` : ''
}

export const api = {
  // —— 认证 ——
  session: () => get<SessionResp>('/session'),
  login: (token: string) => post<{ ok: boolean }>('/login', { token }),
  logout: () => post<{ ok: boolean }>('/logout'),

  // —— 总览 ——
  overview: () => get<Overview>('/overview'),

  // —— Mods ——
  mods: (search?: string, type?: string) => get<{ items: ModItem[] }>(`/mods${qs({ q: search, type })}`),
  patchMod: (file: string, body: Partial<Pick<ModItem, 'name' | 'desc' | 'type'>>) =>
    patch<{ item: ModItem }>(`/mods/${q(file)}`, body),
  deleteMod: (file: string) => del<{ ok: boolean; recycleId: string }>(`/mods/${q(file)}`),
  uploadMod: (fd: FormData) =>
    upload<{ item: ModItem; detected: { name?: string; desc?: string } }>('/mods/upload', fd),

  // —— 其它目录 ——
  dirs: () => get<{ dirs: DirEntry[] }>('/dirs'),
  tree: (dir: string) => get<{ dir: string; entries: TreeEntry[] }>(`/tree?dir=${q(dir)}`),
  uploadToDir: (fd: FormData) => upload<{ ok: boolean; path: string }>('/dirs/upload', fd),
  deleteDirFile: (path: string) => del<{ ok: boolean; recycleId: string }>(`/dirs/file?path=${q(path)}`),

  // —— Config 在线编辑 ——
  configTree: () => get<{ entries: ConfigTreeEntry[] }>('/config/tree'),
  configFile: (path: string) => get<ConfigFile>(`/config/file?path=${q(path)}`),
  saveConfigFile: (path: string, content: string, note?: string) =>
    put<{ ok: boolean; historyId: string }>('/config/file', { path, content, note }),

  // —— 回收站 ——
  recycle: () => get<{ items: RecycleItem[]; settings: RecycleSettings }>('/recycle'),
  restoreRecycle: (id: string) => post<{ ok: boolean; path: string }>(`/recycle/${q(id)}/restore`),
  deleteRecycle: (id: string) => del<{ ok: boolean }>(`/recycle/${q(id)}`),
  purgeRecycle: () => post<{ ok: boolean; removed: number }>('/recycle/purge'),
  saveRecycleSettings: (keepDays: number, keepBytes: number) =>
    put<{ settings: RecycleSettings }>('/recycle/settings', { keepDays, keepBytes }),

  // —— 版本历史 ——
  history: (path: string) => get<{ items: HistoryItem[] }>(`/history?path=${q(path)}`),
  historyContentUrl: (id: string) => `${API_BASE}/history/${q(id)}/content`,
  restoreHistory: (id: string) => post<{ ok: boolean; path: string }>(`/history/${q(id)}/restore`),

  // —— 清单刷新 / 同步 / 实例 ——
  manifestRefresh: () => post<{ ok: boolean; version: number }>('/manifest/refresh'),
  syncPreview: () => get<SyncPreview>('/sync/preview'),
  syncApply: () =>
    post<{ ok: boolean; added: number; updated: number; removed: number; version: number }>('/sync/apply'),
  instanceStatus: () => get<InstanceStatus>('/instance/status'),
  instanceRestart: () => post<{ ok: boolean; message: string }>('/instance/restart'),

  // —— Agent 包 ——
  agent: () => get<AgentInfo>('/agent'),
  uploadAgent: (fd: FormData) => upload<{ ok: boolean; latest: AgentLatest }>('/agent/upload', fd),
  agentDownloadUrl: () => `${API_BASE}/agent/download`,
}

export type { TimeLike }
