import { onScopeDispose, shallowRef } from 'vue'
import { ApiError } from '../services/apiClient.ts'

export function useAsyncRequest<T>(load: (signal: AbortSignal) => Promise<T>) {
  const phase = shallowRef<'idle' | 'loading' | 'success' | 'error'>('idle')
  const data = shallowRef<T | null>(null)
  const error = shallowRef<ApiError | null>(null)
  let controller: AbortController | undefined
  let sequence = 0

  function cancel() {
    sequence++
    controller?.abort()
    controller = undefined
    if (phase.value === 'loading') phase.value = 'idle'
  }

  async function execute() {
    cancel()
    const current = sequence
    const active = new AbortController()
    controller = active
    error.value = null
    phase.value = 'loading'
    try {
      const result = await load(active.signal)
      if (current !== sequence || active.signal.aborted) return
      data.value = result
      phase.value = 'success'
    } catch (failure) {
      if (current !== sequence || active.signal.aborted) return
      error.value = failure instanceof ApiError ? failure : new ApiError('UNEXPECTED_ERROR', 0, '请求暂时不可用，请稍后重试')
      phase.value = 'error'
    } finally {
      if (current === sequence) controller = undefined
    }
  }

  // Cancels only the browser wait; never calls a Run cancellation endpoint.
  onScopeDispose(cancel)
  function reset() { cancel(); data.value = null; error.value = null; phase.value = 'idle' }
  return { phase, data, error, execute, cancel, reset }
}
