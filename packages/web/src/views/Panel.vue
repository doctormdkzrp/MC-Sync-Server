<template>
  <div class="app-shell">
    <header class="top">
      <div class="row between">
        <div class="row">
          <h1>文件柜管理台</h1>
          <span class="pill" :class="conn.cls">{{ conn.label }}</span>
          <span v-if="ov" class="muted">
            权威库版本 <b>{{ ov.version }}</b> · mods {{ ov.mods }} 个 · config {{ ov.configFiles }} 个 ·
            最后上交 {{ lastSubmitText }}
          </span>
          <span v-else class="muted">总览加载中…</span>
        </div>
        <div class="row">
          <button class="btn" :disabled="busy" @click="refreshManifest">刷新清单</button>
          <button class="btn" :disabled="busy" @click="logout">退出</button>
        </div>
      </div>
    </header>

    <nav class="tabs">
      <router-link class="tab" active-class="on" to="/mods">Mods</router-link>
      <router-link class="tab" active-class="on" to="/config">Config</router-link>
      <router-link class="tab" active-class="on" to="/other">其它目录</router-link>
      <router-link class="tab" active-class="on" to="/recycle">
        回收站 <span class="pill gray">{{ recycleCount }}</span>
      </router-link>
    </nav>

    <main>
      <p v-if="overviewState.error" class="warn" style="margin-bottom: 12px">
        总览获取失败：{{ overviewState.error }}
      </p>
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { markAuthed } from '../router'
import { refreshOverview, overviewState } from '../store'
import { fmtTime } from '../format'
import { confirmBox, fail, ok, toMessage } from '../ui'

const router = useRouter()
const busy = ref(false)

const ov = computed(() => overviewState.data)
const recycleCount = computed(() => ov.value?.recycle?.count ?? 0)

const conn = computed<{ cls: string; label: string }>(() => {
  const running = ov.value?.instance?.running
  if (running === true) return { cls: 'green', label: 'E3 已连接' }
  if (running === false) return { cls: 'red', label: '实例未运行' }
  return { cls: 'gray', label: '状态未知' }
})

const lastSubmitText = computed(() => {
  const ls = ov.value?.lastSubmit
  if (!ls || !ls.at) return '还没人上交'
  const who = ls.profile ? `（${ls.profile}）` : ''
  return `${fmtTime(ls.at)}${who}`
})

onMounted(() => {
  void refreshOverview()
})

async function refreshManifest(): Promise<void> {
  busy.value = true
  try {
    const res = await api.manifestRefresh()
    ok(`清单已刷新：版本 ${res.version}`)
    await refreshOverview()
  } catch (e) {
    fail(toMessage(e))
  } finally {
    busy.value = false
  }
}

async function logout(): Promise<void> {
  if (!(await confirmBox('确定退出登录？', '退出'))) return
  try {
    await api.logout()
  } catch {
    /* 忽略：无论后端是否报错都跳登录页 */
  }
  markAuthed(false)
  void router.replace({ name: 'login' })
}
</script>
