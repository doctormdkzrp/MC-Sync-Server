import { reactive } from 'vue'
import { api } from './api'
import type { Overview } from './types'

export const overviewState = reactive<{
  data: Overview | null
  loading: boolean
  error: string
}>({
  data: null,
  loading: false,
  error: '',
})

export async function refreshOverview(): Promise<void> {
  overviewState.loading = true
  try {
    overviewState.data = await api.overview()
    overviewState.error = ''
  } catch (e) {
    overviewState.error = e instanceof Error ? e.message : String(e)
  } finally {
    overviewState.loading = false
  }
}
