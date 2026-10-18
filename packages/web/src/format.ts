import type { ModType, TimeLike } from './types'

export function fmtBytes(n?: number | null): string {
  if (n == null || Number.isNaN(n)) return '—'
  const v = Number(n)
  if (v < 1024) return `${v} B`
  const kb = v / 1024
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(2) : kb < 100 ? kb.toFixed(1) : Math.round(kb)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(2)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

const pad = (n: number): string => String(n).padStart(2, '0')

export function fmtTime(v: TimeLike): string {
  if (v == null || v === '') return '—'
  let d: Date
  if (typeof v === 'number') {
    // 秒级时间戳补成毫秒
    d = new Date(v < 1e12 ? v * 1000 : v)
  } else {
    const s = String(v)
    // 纯数字字符串（可能来自 JSON number→string）
    if (/^\d+$/.test(s)) {
      const num = Number(s)
      d = new Date(num < 1e12 ? num * 1000 : num)
    } else {
      d = new Date(s)
    }
  }
  if (Number.isNaN(d.getTime())) return String(v)
  const now = new Date()
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const sameDay =
    d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  if (sameDay) return `今天 ${hm}`
  const md = `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  if (d.getFullYear() === now.getFullYear()) return `${md} ${hm}`
  return `${d.getFullYear()}-${md} ${hm}`
}

export interface TypeMeta {
  cls: 'green' | 'blue' | 'gray' | 'amber'
  label: string
  /** 是否会被同步到 MC 服务端（both / server） */
  toServer: boolean
}

export function typeMeta(t: ModType | string | undefined): TypeMeta {
  switch (t) {
    case 'both':
      return { cls: 'green', label: '两边都要', toServer: true }
    case 'server':
      return { cls: 'blue', label: '服务端也要', toServer: true }
    case 'client':
      return { cls: 'gray', label: '只有客户端', toServer: false }
    default:
      return { cls: 'amber', label: '待确认', toServer: false }
  }
}

export const MOD_TYPE_OPTIONS: { label: string; value: ModType }[] = [
  { label: '两边都要', value: 'both' },
  { label: '服务端也要', value: 'server' },
  { label: '只有客户端', value: 'client' },
]

/** 目录规则展示：owner/tier/mode → 中文 pill（信息不足时退回原始值） */
export function dirRule(d: { owner?: string; tier?: string; mode?: string; path?: string }): {
  cls: 'green' | 'blue' | 'gray'
  label: string
} {
  const owner = (d.owner || '').toLowerCase()
  const mode = (d.mode || '').toLowerCase()
  const tier = (d.tier || '').toLowerCase()
  const path = (d.path || '').toLowerCase()
  if (mode.includes('union') || owner.includes('profile') || path.startsWith('profiles')) {
    return { cls: 'green', label: '并集·按账号' }
  }
  if (tier === 'authoritative' || owner === 'both' || mode.includes('mirror')) {
    return { cls: 'blue', label: '服务器说了算' }
  }
  const raw = [d.tier, d.owner, d.mode].filter(Boolean).join(' / ')
  return { cls: 'gray', label: raw || '—' }
}

export function basename(p: string): string {
  const parts = (p || '').split('/')
  return parts[parts.length - 1] || p
}

export function gbToBytes(gb: number): number {
  return Math.max(0, Math.round(gb * 1024 * 1024 * 1024))
}

export function bytesToGb(bytes: number): number {
  return Math.round((bytes / (1024 * 1024 * 1024)) * 100) / 100
}
