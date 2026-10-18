<template>
  <section>
    <div class="grid2">
      <div class="tree">
        <div
          v-for="e in sortedEntries"
          :key="e.path"
          class="ti"
          :class="{ on: active === e.path }"
          :style="{ paddingLeft: 6 + depth(e.path) * 14 + 'px' }"
          :title="e.path"
          @click="openFile(e.path)"
        >
          {{ displayPath(e.path) }}
        </div>
        <div v-if="!entries.length && !loadingTree" class="ti" style="cursor: default; color: #9ca3af">
          {{ loadingTree ? '加载中…' : '没有可编辑的 config 文件' }}
        </div>
      </div>

      <div class="editor">
        <div class="tabs2">
          <b class="mono" style="font-size: 13px">{{ active || '（左侧选一个文件）' }}</b>
          <span v-if="active" class="pill gray">最后改：{{ fmtTime(meta.mtime) }}</span>
          <span v-if="active" class="pill gray">{{ fmtBytes(meta.size) }}</span>
          <span v-if="dirty" class="pill amber">未保存</span>
          <span style="flex: 1"></span>
          <button class="btn sm" :disabled="!active || readOnly || !dirty || saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button class="btn sm" :disabled="!active || !dirty" @click="discard">放弃改动</button>
          <button v-if="active" class="btn sm" @click="openHistory">历史</button>
        </div>

        <div v-if="!active" class="wrap-empty" style="padding: 60px 20px">
          左侧选一个 config 文件即可在线编辑（带行号、可搜索 Ctrl/Cmd+F）。
        </div>
        <template v-else>
          <div v-if="meta.binary" class="warn" style="margin: 10px">
            这是二进制或超大（&gt;2MB）文件，只读，不能在线改。
          </div>
          <CodeEditor
            :key="active"
            v-model="content"
            :readonly="readOnly"
            :json-mode="isJson"
          />
        </template>
      </div>
    </div>

    <p class="note" style="margin-top: 10px">
      Config 只给“能改”：带行号、能搜索、保存前自动留旧版（点“历史”能一键还原）；没有 mod 那些“名字/简介/类型”字段。
    </p>
  </section>

  <HistoryDialog v-model:visible="history.visible" :path="history.path" @restored="onRestored" />
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../api'
import { refreshOverview } from '../store'
import { fmtBytes, fmtTime } from '../format'
import { confirmBox, fail, ok, toMessage } from '../ui'
import CodeEditor from '../components/CodeEditor.vue'
import HistoryDialog from '../components/HistoryDialog.vue'
import type { ConfigTreeEntry, TimeLike } from '../types'

const entries = ref<ConfigTreeEntry[]>([])
const loadingTree = ref(false)
const active = ref('')
const content = ref('')
const original = ref('')
const saving = ref(false)
const meta = reactive<{ size: number; mtime: TimeLike; binary: boolean }>({ size: 0, mtime: null, binary: false })

const dirty = computed(() => active.value !== '' && content.value !== original.value)
const readOnly = computed(() => meta.binary)
const isJson = computed(() => active.value.toLowerCase().endsWith('.json'))

const sortedEntries = computed(() => [...entries.value].sort((a, b) => a.path.localeCompare(b.path)))

function displayPath(p: string): string {
  return p.replace(/^config\//, '')
}
function depth(p: string): number {
  return Math.max(0, displayPath(p).split('/').length - 1)
}

async function loadTree(): Promise<void> {
  loadingTree.value = true
  try {
    const res = await api.configTree()
    entries.value = res.entries || []
  } catch (e) {
    fail(toMessage(e))
  } finally {
    loadingTree.value = false
  }
}

onMounted(loadTree)

async function openFile(path: string): Promise<void> {
  if (path === active.value) return
  if (dirty.value && !(await confirmBox('当前文件的改动还没保存，切换会丢失改动。继续？', '放弃改动', '放弃并切换'))) {
    return
  }
  active.value = path
  try {
    const f = await api.configFile(path)
    original.value = f.content ?? ''
    content.value = f.content ?? ''
    meta.size = f.size
    meta.mtime = f.mtime
    meta.binary = !!f.binary
  } catch (e) {
    fail(toMessage(e))
  }
}

async function save(): Promise<void> {
  if (!active.value || readOnly.value || !dirty.value) return
  saving.value = true
  try {
    const res = await api.saveConfigFile(active.value, content.value)
    original.value = content.value
    ok(`已保存：旧版自动留档${res.historyId ? `（历史 ${res.historyId}）` : ''}`)
    try {
      const f = await api.configFile(active.value)
      meta.size = f.size
      meta.mtime = f.mtime
    } catch {
      /* mtime 刷新失败不影响保存结果 */
    }
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    saving.value = false
  }
}

async function discard(): Promise<void> {
  if (!dirty.value) return
  if (!(await confirmBox('放弃改动，恢复到最后保存的样子？', '放弃改动', '放弃'))) return
  content.value = original.value
}

const history = reactive({ visible: false, path: '' })
function openHistory(): void {
  if (!active.value) return
  history.path = active.value
  history.visible = true
}

async function onRestored(): Promise<void> {
  if (active.value) await openFile(active.value)
  await refreshOverview()
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  return confirmBox('Config 有改动还没保存，确定离开？', '未保存的改动', '离开')
})
</script>
