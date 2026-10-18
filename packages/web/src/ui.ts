import { ElMessage, ElMessageBox } from 'element-plus'

/** 统一确认框（中文按钮） */
export function confirmBox(message: string, title = '请确认', confirmText = '确认'): Promise<boolean> {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: confirmText,
    cancelButtonText: '取消',
    type: 'warning',
    dangerouslyUseHTMLString: false,
  })
    .then(() => true)
    .catch(() => false)
}

export function ok(msg: string): void {
  ElMessage.success(msg)
}

export function warn(msg: string): void {
  ElMessage.warning(msg)
}

export function fail(msg: string): void {
  ElMessage.error(msg)
}

export function toMessage(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
