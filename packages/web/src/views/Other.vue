<template>
  <section>
    <div class="panel">
      <div class="bar">
        <b style="font-size: 13.5px">其它目录</b>
        <span class="muted">—— 看清“多大、啥时候、谁动的”，也能上传/删除</span>
        <span style="flex: 1"></span>
        <button class="btn sm" :disabled="loadingDirs" @click="loadDirs">刷新目录</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>目录 / 文件</th>
            <th>规则</th>
            <th class="dense-only">大小</th>
            <th>文件数</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in dirs" :key="d.path" :class="{ 'row-on': d.path === activeDir }">
            <td class="mono">{{ d.path }}/</td>
            <td>
              <span class="pill" :class="dirRule(d).cls">{{ dirRule(d).label }}</span>
            </td>
            <td class="dense-only">{{ fmtBytes(d.bytes) }}</td>
            <td>{{ d.fileCount ?? 0 }} 个</td>
            <td class="right">
              <button class="btn sm" @click="openDir(d.path)">查看</button>
            </td>
          </tr>
          <tr v-if="!loadingDirs && !dirs.length">
            <td colspan="5" class="wrap-empty">没有可管理的目录</td>
          </tr>
          <tr v-if="loadingDirs">
            <td colspan="5" class="wrap-empty">加载中…</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="activeDir" class="panel">
      <div class="bar">
        <b class="mono" style="font-size: 13px">{{ activeDir }}/</b>
        <span class="muted">{{ entries.length }} 个文件</span>
        <span style="flex: 1"></span>
        <button class="btn primary sm" @click="openUpload">＋ 上传到该目录</button>
        <button class="btn sm" :disabled="loadingTree" @click="loadTree(activeDir)">刷新</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>名称</th>
            <th class="dense-only">仓库路径</th>
            <th class="dense-only">大小</th>
            <th>最后修改</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in entries" :key="f.path">
            <td class="mono">{{ f.name }}</td>
            <td class="mono dense-only">{{ f.path }}</td>
            <td class="dense-only">{{ fmtBytes(f.size) }}</td>
            <td>{{ fmtTime(f.mtime) }}</td>
            <td class="right">
              <button class="btn sm danger" @click="removeFile(f)">删除</button>
            </td>
          </tr>
          <tr v-if="!loadingTree && !entries.length">
            <td colspan="5" class="wrap-empty">这个目录目前是空的</td>
          </tr>
          <tr v-if="loadingTree">
            <td colspan="5" class="wrap-empty">加载中…</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="note" style="margin-top: 10px">
      存档（saves）、日志、备份这些是禁区，面板里不会有入口。删除同样进回收站，能一键恢复。
    </p>

    <el-dialog v-model="upload.visible" title="上传到目录" width="560px" @closed="resetUpload">
      <div class="field">
        <label>目标目录</label>
        <el-input v-model="upload.dir" disabled />
      </div>
      <div class="field">
        <label>子目录（可选，例如 textures/blocks）</label>
        <el-input v-model="upload.subdir" placeholder="留空则放在目录根下" />
      </div>
      <div class="field">
        <label>选择文件</label>
        <input ref="fileInput" type="file" style="width: 100%" @change="onFileChange" />
      </div>
      <div class="note">上传不做名字/简介/类型，直接放进目录（同一个 mods 一样会被清单实时看到）。</div>
      <template #footer>
        <button class="btn" @click="upload.visible = false">取消</button>
        <button class="btn primary" :disabled="!upload.file || upload.busy" @click="doUpload">
          {{ upload.busy ? '上传中…' : '上传' }}
        </button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { refreshOverview } from '../store'
import { dirRule, fmtBytes, fmtTime } from '../format'
import { confirmBox, fail, ok, toMessage, warn } from '../ui'
import type { DirEntry, TreeEntry } from '../types'

const dirs = ref<DirEntry[]>([])
const activeDir = ref('')
const entries = ref<TreeEntry[]>([])
const loadingDirs = ref(false)
const loadingTree = ref(false)

async function loadDirs(): Promise<void> {
  loadingDirs.value = true
  try {
    const res = await api.dirs()
    dirs.value = res.dirs || []
    if (activeDir.value && !dirs.value.some((d) => d.path === activeDir.value)) {
      activeDir.value = ''
      entries.value = []
    }
  } catch (e) {
    fail(toMessage(e))
  } finally {
    loadingDirs.value = false
  }
}

async function loadTree(dir: string): Promise<void> {
  loadingTree.value = true
  try {
    const res = await api.tree(dir)
    entries.value = res.entries || []
  } catch (e) {
    fail(toMessage(e))
  } finally {
    loadingTree.value = false
  }
}

onMounted(loadDirs)

async function openDir(dir: string): Promise<void> {
  activeDir.value = dir
  entries.value = []
  await loadTree(dir)
}

async function removeFile(f: TreeEntry): Promise<void> {
  if (!(await confirmBox(`删除「${f.path}」？会移进回收站，随时能恢复。`, '删除文件', '删除'))) return
  try {
    await api.deleteDirFile(f.path)
    ok(`已移进回收站：${f.path}`)
    await loadTree(activeDir.value)
    await loadDirs()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  }
}

// —— 上传 ——
const fileInput = ref<HTMLInputElement | null>(null)
const upload = reactive({ visible: false, dir: '', subdir: '', file: null as File | null, busy: false })

function openUpload(): void {
  upload.dir = activeDir.value
  upload.visible = true
}

function resetUpload(): void {
  upload.subdir = ''
  upload.file = null
  upload.busy = false
  if (fileInput.value) fileInput.value.value = ''
}

function onFileChange(e: Event): void {
  const input = e.target as HTMLInputElement
  upload.file = input.files && input.files[0] ? input.files[0] : null
}

async function doUpload(): Promise<void> {
  if (!upload.file) {
    warn('先选一个文件')
    return
  }
  const fd = new FormData()
  fd.append('dir', upload.dir)
  fd.append('file', upload.file)
  if (upload.subdir.trim()) fd.append('subdir', upload.subdir.trim())
  upload.busy = true
  try {
    const res = await api.uploadToDir(fd)
    ok(`已上传：${res.path || upload.file.name}`)
    upload.visible = false
    await loadTree(activeDir.value)
    await loadDirs()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    upload.busy = false
  }
}
</script>
