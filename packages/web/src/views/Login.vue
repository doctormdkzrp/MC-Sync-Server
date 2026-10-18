<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1>文件柜管理台</h1>
      <p class="note" style="margin-bottom: 16px">输入管理台口令登录。</p>
      <div class="field">
        <label>口令</label>
        <el-input
          v-model="token"
          type="password"
          show-password
          placeholder="管理台口令"
          size="large"
          @keyup.enter="submit"
        />
      </div>
      <button class="btn primary" style="width: 100%; padding: 9px" :disabled="busy" @click="submit">
        {{ busy ? '登录中…' : '登录' }}
      </button>
      <p v-if="error" class="warn" style="margin-top: 12px">{{ error }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { markAuthed } from '../router'
import { toMessage } from '../ui'

const token = ref('')
const busy = ref(false)
const error = ref('')
const router = useRouter()
const route = useRoute()

onMounted(async () => {
  try {
    const s = await api.session()
    if (s.authed) {
      markAuthed(true)
      goNext()
    }
  } catch {
    /* 未登录，留在登录页 */
  }
})

function goNext(): void {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  void router.replace(redirect)
}

async function submit(): Promise<void> {
  if (!token.value) {
    error.value = '请先输入口令'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api.login(token.value)
    markAuthed(true)
    goNext()
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}
</script>
