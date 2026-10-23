import { ApiError, isAbortError } from './apiClient.ts'
import type { Fetcher } from './apiClient.ts'

/** Actuator intentionally retains its own response shape, outside the business envelope. */
export async function checkBackend(signal: AbortSignal, fetcher: Fetcher = globalThis.fetch): Promise<void> {
  try {
    const response = await fetcher('/actuator/health/readiness', { signal, headers: { Accept: 'application/json' } })
    const body: unknown = await response.json()
    if (!response.ok || typeof body !== 'object' || body === null || !('status' in body) || body.status !== 'UP') {
      throw new ApiError('BACKEND_NOT_READY', response.status, '本地服务尚未就绪，请检查后端与数据库连接')
    }
  } catch (error) {
    if (signal.aborted || isAbortError(error) || error instanceof ApiError) throw error
    throw new ApiError('NETWORK_ERROR', 0, '无法连接本地服务，请确认服务已启动后重试')
  }
}
