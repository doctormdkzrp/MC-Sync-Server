<template>
  <section :class="{ guided }">
    <div class="panel">
      <div class="bar">
        <button class="btn primary" @click="openUpload">＋ 上传 mod</button>
        <button class="btn" :disabled="busy" @click="openSync">同步到 MC 服务端</button>
        <button class="btn" :disabled="busy" @click="restartInstance">重启服务端实例</button>
        <button class="btn" :disabled="busy" @click="refreshManifest">刷新清单</button>
        <span style="flex: 1"></span>
        <el-input v-model="q" placeholder="搜文件名 / 名字 / 简介" style="width: 230px" clearable />
        <el-select v-model="typeFilter" style="width: 130px" placeholder="全部类型">
          <el-option label="全部类型" value="" />
          <el-option v-for="o in MOD_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <label class="muted row" style="gap: 6px; cursor: pointer">
          <input v-model="guided" type="checkbox" />向导式（大行距）
        </label>
      </div>

      <table>
        <thead>
          <tr>
            <th style="width: 28px">
              <input type="checkbox" :checked="allSelected" @change="toggleAll" />
            </th>
            <th>文件名</th>
            <th>名字</th>
            <th>简介</th>
            <th>类型</th>
            <th class="dense-only">大小</th>
            <th>上传时间</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in filtered" :key="m.file" :data-type="m.type">
            <td><input v-model="selected" type="checkbox" :value="m.file" /></td>
            <td class="mono">{{ m.file }}</td>

            <td v-if="editing === m.file">
              <el-input v-model="editForm.name" size="small" placeholder="名字" />
            </td>
            <td v-else>{{ m.name || '（未填名字）' }}</td>

            <td v-if="editing === m.file" class="desc" style="max-width: 360px">
              <el-input v-model="editForm.desc" size="small" placeholder="简介" />
            </td>
            <td v-else class="desc">{{ m.desc || '（还没填简介 —— 点“编辑”补上）' }}</td>

            <td v-if="editing === m.file">
              <el-select v-model="editForm.type" size="small" style="width: 130px">
                <el-option v-for="o in MOD_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </td>
            <td v-else>
              <span class="pill" :class="typeMeta(m.type).cls">{{ typeMeta(m.type).label }}</span>
            </td>

            <td class="dense-only">{{ fmtBytes(m.size) }}</td>
            <td>{{ fmtTime(m.uploadedAt || m.mtime) }}</td>
            <td class="right">
              <template v-if="editing === m.file">
                <button class="btn sm primary" @click="saveEdit(m)">保存</button>
                <button class="btn sm" @click="cancelEdit">取消</button>
              </template>
              <template v-else>
                <button class="btn sm" @click="startEdit(m)">编辑</button>
                <button class="btn sm" @click="openHistory(m)">历史</button>
                <button class="btn sm danger" @click="removeMod(m)">删除</button>
              </template>
            </td>
          </tr>
          <tr v-if="!loading && filtered.length === 0">
            <td colspan="8" class="wrap-empty">没有匹配的 mod</td>
          </tr>
          <tr v-if="loading">
            <td colspan="8" class="wrap-empty">加载中…</td>
          </tr>
        </tbody>
      </table>

      <div class="bar foot">
        <span class="muted">共 {{ mods.length }} 个 · 当前显示 {{ filtered.length }} 个 · 已选 {{ selected.length }} 个</span>
        <span style="flex: 1"></span>
        <button class="btn sm" :disabled="!selected.length" @click="batchDelete">批量删除</button>
        <button class="btn sm" :disabled="!selected.length" @click="batchTypeVisible = true">批量改类型</button>
      </div>
    </div>

    <p class="note" style="margin-top: 10px">
      说明：<b>名字</b>和<b>简介</b>是上传时从 jar 里自动读出来的（GTNH 的 jar 自带），你只需要点一下<b>类型</b>；纯客户端的
      28 个已经预先标好，剩下的默认“两边都要”。<b>删除</b>是移进回收站，清单里立刻消失、文件柜里也移走，但随时能恢复。
    </p>

    <!-- ============ 上传 mod ============ -->
    <el-dialog v-model="upload.visible" title="上传 mod" width="640px" @closed="resetUpload">
      <div class="field">
        <label>选择文件（.jar）</label>
        <input ref="fileInput" type="file" accept=".jar,.zip" style="width: 100%" @change="onFileChange" />
      </div>
      <div class="note" style="margin: -4px 0 10px">
        选好后点“上传并读取信息”，后端会从 jar 里自动读出名字和简介，下面两行可以改。
      </div>
      <div class="field">
        <label>名字（自动读出，可改）</label>
        <el-input v-model="upload.name" placeholder="留空则由后端自动读取" />
      </div>
      <div class="field">
        <label>简介（自动读出，可改）</label>
        <el-input v-model="upload.desc" type="textarea" :rows="2" placeholder="留空则由后端自动读取" />
      </div>
      <div class="field">
        <label>类型</label>
        <el-select v-model="upload.type" style="width: 100%">
          <el-option v-for="o in MOD_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
      <div v-if="uploadSameFile" class="warn">
        同名文件已存在（{{ uploadSameFile }}）→ 上传会覆盖旧文件，旧版会自动留历史，建议之后点“同步到 MC 服务端”。
      </div>
      <div v-else-if="uploadSameName" class="warn">
        已有同名 mod（{{ uploadSameName }}）→ 确认是不是要上传新版。
      </div>
      <template #footer>
        <button class="btn" @click="upload.visible = false">取消</button>
        <button class="btn" :disabled="!upload.file || upload.busy" @click="uploadRead">
          {{ upload.reading ? '读取中…' : '上传并读取信息' }}
        </button>
        <button class="btn primary" :disabled="!upload.uploaded || upload.busy" @click="saveUploadMeta">
          保存
        </button>
      </template>
    </el-dialog>

    <!-- ============ 同步到 MC 服务端 ============ -->
    <el-dialog v-model="sync.visible" title="同步到 MC 服务端 —— 先看清楚再动手" width="680px">
      <div v-if="sync.loading" class="note">正在计算差异…</div>
      <template v-else-if="sync.preview">
        <div class="ok">
          刚算过一遍：<b>新增 {{ sync.preview.adds.length }} 个</b>、<b>覆盖 {{ sync.preview.updates.length }} 个</b>、<b>删除
            {{ sync.preview.removes.length }} 个</b>、不动 {{ sync.preview.unchanged }} 个。
        </div>
        <p v-if="sync.preview.target" class="note" style="margin: 8px 0 0">
          目标：mods → {{ sync.preview.target.modsDir || '—' }} ；config → {{ sync.preview.target.configDir || '—' }}
        </p>

        <div class="field" style="margin-top: 12px">
          <label>会被放进服务端 mods 的（新增）</label>
          <ul class="plain">
            <li v-for="f in sync.preview.adds" :key="f" class="mono">{{ f }}</li>
            <li v-if="!sync.preview.adds.length">（无变化）</li>
          </ul>
        </div>
        <div class="field">
          <label>会被覆盖的（服务端已有的旧版会进历史/回收站）</label>
          <ul class="plain">
            <li v-for="f in sync.preview.updates" :key="f" class="mono">{{ f }}</li>
            <li v-if="!sync.preview.updates.length">（无变化）</li>
          </ul>
        </div>
        <div class="field">
          <label>会被移进回收站的（服务端多出来的）</label>
          <ul class="plain">
            <li v-for="f in sync.preview.removes" :key="f" class="mono">{{ f }}</li>
            <li v-if="!sync.preview.removes.length">（无——服务端没有多余文件）</li>
          </ul>
        </div>
        <div class="field">
          <label>只会留在客户端、不会进服务端的（{{ clientMods.length }} 个纯客户端 mod）</label>
          <ul class="plain">
            <li v-if="clientMods.length" class="mono">
              {{ clientMods.slice(0, 5).map((m) => m.file).join('、') }}
              <template v-if="clientMods.length > 5">……（共 {{ clientMods.length }} 个）</template>
            </li>
            <li v-else>（无）</li>
          </ul>
        </div>
        <div
          class="field"
          v-if="sync.preview.configSkippedAdds?.length || sync.preview.configSkippedRemoves?.length"
        >
          <label>config 只覆盖、不增删（仓库 config 是客户端全量，不能整树塞给服务端）</label>
          <ul class="plain">
            <li v-if="sync.preview.configSkippedAdds?.length" class="mono">
              服务端没有的 {{ sync.preview.configSkippedAdds.length }} 个 config 文件<b>不推</b>（多为客户端专属）
            </li>
            <li v-if="sync.preview.configSkippedRemoves?.length" class="mono">
              服务端独有的 {{ sync.preview.configSkippedRemoves.length }} 个 config 文件<b>不动</b>（如 JourneyMapServer、.bak 备份）
            </li>
          </ul>
        </div>
        <div class="warn">
          删掉的东西都进回收站、能一键恢复；服务端 mod 改动<b>要重启实例才生效</b>，执行完我会问你要不要顺手重启。
        </div>
      </template>
      <div v-else-if="sync.error" class="warn">{{ sync.error }}</div>
      <template #footer>
        <button class="btn" @click="sync.visible = false">取消</button>
        <button class="btn primary" :disabled="sync.loading || sync.applying || !sync.preview" @click="applySync">
          {{ sync.applying ? '执行中…' : '确认执行' }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 批量改类型 ============ -->
    <el-dialog v-model="batchTypeVisible" title="批量改类型" width="420px">
      <div class="field">
        <label>把已选的 {{ selected.length }} 个 mod 改成</label>
        <el-select v-model="batchType" style="width: 100%">
          <el-option v-for="o in MOD_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
      <template #footer>
        <button class="btn" @click="batchTypeVisible = false">取消</button>
        <button class="btn primary" @click="applyBatchType">确定</button>
      </template>
    </el-dialog>

    <HistoryDialog v-model:visible="history.visible" :path="history.path" @restored="reload" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { refreshOverview } from '../store'
import { MOD_TYPE_OPTIONS, fmtBytes, fmtTime, typeMeta } from '../format'
import { confirmBox, fail, ok, toMessage, warn } from '../ui'
import HistoryDialog from '../components/HistoryDialog.vue'
import type { ModItem, ModType, SyncPreview } from '../types'

const mods = ref<ModItem[]>([])
const loading = ref(false)
const busy = ref(false)

const q = ref('')
const typeFilter = ref('')
const guided = ref(false)
const selected = ref<string[]>([])

const editing = ref('')
const editForm = reactive<{ name: string; desc: string; type: ModType }>({ name: '', desc: '', type: 'both' })

const filtered = computed(() => {
  const term = q.value.trim().toLowerCase()
  return mods.value.filter((m) => {
    if (typeFilter.value && m.type !== typeFilter.value) return false
    if (!term) return true
    return `${m.file} ${m.name || ''} ${m.desc || ''}`.toLowerCase().includes(term)
  })
})

const clientMods = computed(() => mods.value.filter((m) => m.type === 'client'))

const allSelected = computed(() => filtered.value.length > 0 && filtered.value.every((m) => selected.value.includes(m.file)))

function toggleAll(e: Event): void {
  const checked = (e.target as HTMLInputElement).checked
  if (checked) selected.value = Array.from(new Set([...selected.value, ...filtered.value.map((m) => m.file)]))
  else selected.value = selected.value.filter((f) => !filtered.value.some((m) => m.file === f))
}

async function reload(): Promise<void> {
  loading.value = true
  try {
    const res = await api.mods()
    mods.value = res.items || []
    const files = new Set(mods.value.map((m) => m.file))
    selected.value = selected.value.filter((f) => files.has(f))
    if (editing.value && !files.has(editing.value)) editing.value = ''
  } catch (e) {
    fail(toMessage(e))
  } finally {
    loading.value = false
  }
}

onMounted(reload)

// —— 行内编辑 ——
function startEdit(m: ModItem): void {
  editing.value = m.file
  editForm.name = m.name || ''
  editForm.desc = m.desc || ''
  editForm.type = (m.type as ModType) || 'both'
}

function cancelEdit(): void {
  editing.value = ''
}

async function saveEdit(m: ModItem): Promise<void> {
  try {
    const res = await api.patchMod(m.file, { name: editForm.name, desc: editForm.desc, type: editForm.type })
    const idx = mods.value.findIndex((x) => x.file === m.file)
    if (idx >= 0) mods.value[idx] = { ...mods.value[idx], ...res.item }
    editing.value = ''
    ok('已保存')
  } catch (e) {
    fail(toMessage(e))
  }
}

// —— 删除 ——
async function removeMod(m: ModItem): Promise<void> {
  if (!(await confirmBox(`删除「${m.file}」？会移进回收站，随时能恢复。`, '删除 mod', '删除'))) return
  try {
    await api.deleteMod(m.file)
    ok(`已移进回收站：${m.file}`)
    await reload()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  }
}

// —— 批量 ——
const batchTypeVisible = ref(false)
const batchType = ref<ModType>('both')

async function batchDelete(): Promise<void> {
  if (!selected.value.length) return
  if (!(await confirmBox(`删除已选的 ${selected.value.length} 个 mod？会移进回收站，随时能恢复。`, '批量删除', '删除'))) {
    return
  }
  let done = 0
  for (const f of [...selected.value]) {
    try {
      await api.deleteMod(f)
      done++
    } catch (e) {
      fail(`${f}：${toMessage(e)}`)
    }
  }
  ok(`已移进回收站 ${done} 个`)
  await reload()
  await refreshOverview()
}

async function applyBatchType(): Promise<void> {
  const t = batchType.value
  let done = 0
  for (const f of [...selected.value]) {
    try {
      await api.patchMod(f, { type: t })
      done++
    } catch (e) {
      fail(`${f}：${toMessage(e)}`)
    }
  }
  batchTypeVisible.value = false
  ok(`已改 ${done} 个的类型`)
  await reload()
}

// —— 上传 ——
const fileInput = ref<HTMLInputElement | null>(null)
const upload = reactive({
  visible: false,
  file: null as File | null,
  uploaded: false,
  reading: false,
  busy: false,
  name: '',
  desc: '',
  type: 'both' as ModType,
})

const uploadSameFile = computed(() => {
  if (!upload.file) return ''
  return mods.value.some((m) => m.file === upload.file!.name) ? upload.file.name : ''
})
const uploadSameName = computed(() => {
  const n = upload.name.trim()
  if (!n) return ''
  const hit = mods.value.find((m) => m.name === n && m.file !== upload.file?.name)
  return hit ? hit.file : ''
})

function openUpload(): void {
  upload.visible = true
}

function resetUpload(): void {
  upload.file = null
  upload.uploaded = false
  upload.reading = false
  upload.busy = false
  upload.name = ''
  upload.desc = ''
  upload.type = 'both'
  if (fileInput.value) fileInput.value.value = ''
}

function onFileChange(e: Event): void {
  const input = e.target as HTMLInputElement
  upload.file = input.files && input.files[0] ? input.files[0] : null
  upload.uploaded = false
}

async function uploadRead(): Promise<void> {
  if (!upload.file) {
    warn('先选一个文件')
    return
  }
  const fd = new FormData()
  fd.append('file', upload.file)
  upload.reading = true
  upload.busy = true
  try {
    const res = await api.uploadMod(fd)
    upload.uploaded = true
    upload.name = res.item.name || res.detected?.name || ''
    upload.desc = res.item.desc || res.detected?.desc || ''
    upload.type = (res.item.type as ModType) || 'both'
    ok('已上传并读取信息，确认无误后点“保存”')
    await reload()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    upload.reading = false
    upload.busy = false
  }
}

async function saveUploadMeta(): Promise<void> {
  if (!upload.file) return
  upload.busy = true
  try {
    await api.patchMod(upload.file.name, { name: upload.name, desc: upload.desc, type: upload.type })
    ok('已保存：清单已刷新')
    upload.visible = false
    await reload()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    upload.busy = false
  }
}

// —— 刷新清单 / 同步 / 重启 ——
async function refreshManifest(): Promise<void> {
  busy.value = true
  try {
    const res = await api.manifestRefresh()
    ok(`清单已刷新：版本 ${res.version}`)
    await reload()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    busy.value = false
  }
}

const sync = reactive({
  visible: false,
  loading: false,
  applying: false,
  error: '',
  preview: null as SyncPreview | null,
})

async function openSync(): Promise<void> {
  sync.visible = true
  sync.error = ''
  sync.preview = null
  sync.loading = true
  try {
    sync.preview = await api.syncPreview()
  } catch (e) {
    sync.error = toMessage(e)
  } finally {
    sync.loading = false
  }
}

async function applySync(): Promise<void> {
  sync.applying = true
  try {
    const res = await api.syncApply()
    sync.visible = false
    ok(`已完成：新增 ${res.added}、覆盖 ${res.updated}、删除 ${res.removed}（版本 ${res.version}）`)
    await reload()
    await refreshOverview()
    if (await confirmBox('服务端 mod 改动要重启实例才生效，现在顺手重启吗？', '重启实例', '现在重启')) {
      await doRestart()
    }
  } catch (e) {
    fail(toMessage(e))
  } finally {
    sync.applying = false
  }
}

async function doRestart(): Promise<void> {
  try {
    const res = await api.instanceRestart()
    ok(res.message || '已发出重启指令')
  } catch (e) {
    fail(toMessage(e))
  }
}

async function restartInstance(): Promise<void> {
  if (!(await confirmBox('重启 MC 服务端实例？正在玩的玩家会掉线。', '重启服务端实例', '重启'))) return
  busy.value = true
  try {
    await doRestart()
    await refreshOverview()
  } finally {
    busy.value = false
  }
}

// —— 历史 ——
const history = reactive({ visible: false, path: '' })
function openHistory(m: ModItem): void {
  history.path = `${m.dir || 'mods'}/${m.file}`
  history.visible = true
}
</script>
