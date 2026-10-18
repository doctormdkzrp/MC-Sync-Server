// 与 docs/ADMIN_API.md 一一对应的接口形状。字段名/路径以契约为准，不改。

export type ModType = 'both' | 'server' | 'client'
/** mtime / uploadedAt / savedAt 等时间戳：契约写作 "ISO" / ".."，兼容 ISO 字符串或秒/毫秒数 */
export type TimeLike = number | string | null | undefined

export interface ModItem {
  file: string
  dir: string
  name: string
  desc: string
  type: ModType
  size: number
  sha256?: string
  mtime?: TimeLike
  uploadedAt?: TimeLike
  hasHistory?: boolean
}

export interface Overview {
  version: number
  mods: number
  configFiles: number
  repoBytes: number
  lastSubmit?: { at?: TimeLike; profile?: string } | null
  recycle?: { count?: number; bytes?: number } | null
  instance?: { running?: boolean } | null
}

export interface SessionResp {
  authed: boolean
}

export interface DirEntry {
  path: string
  tier?: string
  owner?: string
  mode?: string
  fileCount?: number
  bytes?: number
}

export interface TreeEntry {
  path: string
  name: string
  size: number
  mtime?: TimeLike
  sha256?: string
}

export interface ConfigTreeEntry {
  path: string
  name: string
  size: number
  mtime?: TimeLike
}

export interface ConfigFile {
  path: string
  content: string
  size: number
  mtime?: TimeLike
  binary: boolean
}

export interface RecycleItem {
  id: string
  path: string
  kind: 'mod' | 'file' | string
  size: number
  deletedAt?: TimeLike
  deletedBy?: string
  note?: string
}

export interface RecycleSettings {
  keepDays: number
  keepBytes: number
  usedBytes?: number
}

export interface HistoryItem {
  id: string
  path: string
  version: number
  size: number
  savedAt?: TimeLike
  savedBy?: string
  note?: string
}

export interface SyncPreview {
  target?: { modsDir?: string; configDir?: string } | null
  adds: string[]
  updates: string[]
  removes: string[]
  unchanged: number
  configSkippedAdds?: string[]
  configSkippedRemoves?: string[]
}

export interface InstanceStatus {
  running: boolean
  instance: string
  mcVersion: string
}

export interface AgentLatest {
  version: string
  sha256?: string
  size?: number
  uploadedAt?: TimeLike
}

export interface AgentClient {
  machine: string
  profile?: string
  agentVersion?: string
  lastSeen?: TimeLike
}

export interface AgentInfo {
  latest?: AgentLatest | null
  clients?: AgentClient[]
}
