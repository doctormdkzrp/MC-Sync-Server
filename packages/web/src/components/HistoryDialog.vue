<template>
  <el-dialog
    :model-value="visible"
    :title="`版本历史 — ${path}`"
    width="680px"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <div v-if="loading" class="note">加载中…</div>
    <table v-else>
      <thead>
        <tr>
          <th>版本</th>
          <th>大小</th>
          <th>保存时间</th>
          <th>谁改的</th>
          <th>备注</th>
          <th class="right">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="it in items" :key="it.id">
          <td class="mono">v{{ it.version }}</td>
          <td>{{ fmtBytes(it.size) }}</td>
          <td>{{ fmtTime(it.savedAt) }}</td>
          <td>{{ it.savedBy || '—' }}</td>
          <td class="desc">{{ it.note || '—' }}</td>
          <td class="right">
            <a class="btn" :href="api.historyContentUrl(it.id)" target="_blank" rel="noopener">查看</a>
            <button class="btn sm primary" @click="restore(it)">还原</button>
          </td>
        </tr>
        <tr v-if="!items.length">
          <td colspan="6" class="wrap-empty">还没有历史版本</td>
        </tr>
      </tbody>
    </table>
    <template #footer>
      <button class="btn" @click="emit('update:visible', false)">关闭</button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../api'
import { fmtBytes, fmtTime } from '../format'
import { fail, ok, toMessage } from '../ui'
import type { HistoryItem } from '../types'

const props = defineProps<{ visible: boolean; path: string }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; restored: [] }>()

const items = ref<HistoryItem[]>([])
const loading = ref(false)

watch(
  () => [props.visible, props.path] as const,
  async ([visible, path]) => {
    if (!visible || !path) return
    loading.value = true
    items.value = []
    try {
      const res = await api.history(path)
      items.value = res.items || []
    } catch (e) {
      fail(toMessage(e))
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

async function restore(it: HistoryItem): Promise<void> {
  try {
    const res = await api.restoreHistory(it.id)
    ok(`已还原到 v${it.version}：${res.path || props.path}`)
    emit('restored')
    emit('update:visible', false)
  } catch (e) {
    fail(toMessage(e))
  }
}
</script>
