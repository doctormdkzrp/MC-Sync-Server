<template>
  <section>
    <div class="panel">
      <div class="bar">
        <b style="font-size: 13.5px">保留策略</b>
        <span class="muted">超了自动清最老的（回收站与版本历史共用）</span>
      </div>
      <div class="bar" style="border-bottom: 0">
        <label class="muted">按天保留</label>
        <el-input-number v-model="keepDays" :min="1" :max="3650" :step="1" size="small" />
        <label class="muted" style="margin-left: 12px">按大小保留（GB）</label>
        <el-input-number v-model="keepGB" :min="0.01" :max="10240" :step="0.5" :precision="2" size="small" />
        <span style="flex: 1"></span>
        <span class="muted">当前占用 {{ fmtBytes(usedBytes) }}</span>
        <button class="btn sm primary" :disabled="saving" @click="saveSettings">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </div>

    <div class="panel">
      <div class="bar">
        <b style="font-size: 13.5px">回收站</b>
        <span class="muted">删的东西都在这儿，随时能恢复</span>
        <span style="flex: 1"></span>
        <button class="btn sm" :disabled="loading" @click="load">刷新</button>
        <button class="btn sm danger" :disabled="!items.length" @click="purge">清空</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>原来在哪</th>
            <th>文件名</th>
            <th>为什么进来</th>
            <th class="dense-only">大小</th>
            <th>什么时候</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="it in items" :key="it.id">
            <td class="mono">{{ dirOf(it.path) }}</td>
            <td class="mono">{{ basename(it.path) }}</td>
            <td class="desc">{{ it.note || '—' }}</td>
            <td class="dense-only">{{ fmtBytes(it.size) }}</td>
            <td>{{ fmtTime(it.deletedAt) }}<span v-if="it.deletedBy" class="muted">（{{ it.deletedBy }}）</span></td>
            <td class="right">
              <button class="btn sm" @click="restore(it)">恢复</button>
              <button class="btn sm danger" @click="destroy(it)">彻底删除</button>
            </td>
          </tr>
          <tr v-if="!loading && !items.length">
            <td colspan="6" class="wrap-empty">回收站是空的</td>
          </tr>
          <tr v-if="loading">
            <td colspan="6" class="wrap-empty">加载中…</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="note" style="margin-top: 10px">
      「恢复」把文件放回原来的位置；「彻底删除」不可撤销。「清空」会把回收站里全部内容永久删除。
    </p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { refreshOverview } from '../store'
import { basename, bytesToGb, fmtBytes, fmtTime, gbToBytes } from '../format'
import { confirmBox, fail, ok, toMessage } from '../ui'
import type { RecycleItem } from '../types'

const items = ref<RecycleItem[]>([])
const loading = ref(false)
const saving = ref(false)
const keepDays = ref(30)
const keepGB = ref(2)
const usedBytes = ref(0)

function dirOf(p: string): string {
  const parts = (p || '').split('/')
  parts.pop()
  return parts.length ? `${parts.join('/')}/` : '—'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await api.recycle()
    items.value = res.items || []
    if (res.settings) {
      keepDays.value = res.settings.keepDays ?? 30
      keepGB.value = bytesToGb(res.settings.keepBytes ?? 0)
      usedBytes.value = res.settings.usedBytes ?? 0
    }
  } catch (e) {
    fail(toMessage(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function saveSettings(): Promise<void> {
  saving.value = true
  try {
    const res = await api.saveRecycleSettings(keepDays.value, gbToBytes(keepGB.value))
    if (res.settings) {
      keepDays.value = res.settings.keepDays ?? keepDays.value
      keepGB.value = bytesToGb(res.settings.keepBytes ?? 0)
      usedBytes.value = res.settings.usedBytes ?? usedBytes.value
    }
    ok('保留策略已保存')
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    saving.value = false
  }
}

async function restore(it: RecycleItem): Promise<void> {
  try {
    const res = await api.restoreRecycle(it.id)
    ok(`已恢复：${res.path || it.path}`)
    await load()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  }
}

async function destroy(it: RecycleItem): Promise<void> {
  if (!(await confirmBox(`彻底删除「${it.path}」？不可撤销。`, '彻底删除', '彻底删除'))) return
  try {
    await api.deleteRecycle(it.id)
    ok('已彻底删除')
    await load()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  }
}

async function purge(): Promise<void> {
  if (!(await confirmBox('清空回收站？里面所有内容都会被永久删除，不可撤销。', '清空回收站', '清空'))) return
  try {
    const res = await api.purgeRecycle()
    ok(`已清空，移除 ${res.removed} 项`)
    await load()
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  }
}
</script>
