/** Adapted from WealthFlow's fetch client; InvestLens uses a string-code envelope. */
export interface FieldError {
  readonly field: string | null
  readonly code: string
  readonly message: string
}

interface Envelope<T> {
  schemaVersion: '1.0.0'
  code: string
  message: string
  data: T
  errors: FieldError[]
  traceId: string
  timestamp: string
}

export interface ApiResult<T> {
  readonly status: number
  readonly code: 'OK' | 'RUN_IN_PROGRESS'
  readonly data: T
  readonly traceId: string
  readonly timestamp: string
  readonly location: string | null
  readonly retryAfter: string | null
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly errors: readonly FieldError[]
  readonly traceId: string | null
  readonly retryAfter: string | null

  constructor(code: string, status: number, message: string, errors: readonly FieldError[] = [],
    traceId: string | null = null, retryAfter: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.errors = errors.map(error => Object.freeze({ ...error }))
    this.traceId = traceId
    this.retryAfter = retryAfter
  }
}

export type Fetcher = typeof fetch
export type RequestOptions = Omit<RequestInit, 'method' | 'body'> & { idempotencyKey?: string }
type JsonRequest = RequestOptions & { method?: string; body?: unknown }

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function envelope(value: unknown): value is Envelope<unknown> {
  if (!object(value) || Object.keys(value).length !== 7 || value.schemaVersion !== '1.0.0'
    || typeof value.code !== 'string' || !/^[A-Z][A-Z0-9_]*$/.test(value.code)
    || typeof value.message !== 'string' || !('data' in value) || !Array.isArray(value.errors)
    || typeof value.traceId !== 'string' || typeof value.timestamp !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value.timestamp)) return false
  return value.errors.every(error => object(error) && (error.field === null || typeof error.field === 'string')
    && typeof error.code === 'string' && typeof error.message === 'string')
}

export function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

export function createApiClient(baseUrl = '/api', fetcher: Fetcher = globalThis.fetch) {
  async function request<T>(path: string, options: JsonRequest = {}): Promise<ApiResult<T>> {
    if (!path.startsWith('/') || path.startsWith('//')) {
      throw new ApiError('INVALID_REQUEST', 0, 'API 路径格式不正确')
    }
    const { body, idempotencyKey, ...init } = options
    const headers = new Headers(options.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    if (body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    const existingKey = headers.get('Idempotency-Key')
    if (idempotencyKey !== undefined && existingKey !== null && existingKey !== idempotencyKey) {
      throw new ApiError('INVALID_REQUEST', 0, '幂等键设置不一致')
    }
    const key = idempotencyKey ?? existingKey
    if (key !== null && key !== undefined) {
      if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(key)) throw new ApiError('INVALID_REQUEST', 0, '幂等键格式不正确')
      headers.set('Idempotency-Key', key)
    }
    let serialized: string | undefined
    if (body !== undefined) {
      try {
        serialized = JSON.stringify(body)
        if (serialized === undefined) throw new TypeError()
      } catch {
        throw new ApiError('INVALID_REQUEST', 0, '请求内容无法编码为 JSON')
      }
    }
    let response: Response
    try {
      response = await fetcher(`${baseUrl.replace(/\/$/, '')}${path}`, {
        credentials: 'same-origin', ...init, headers,
        ...(serialized === undefined ? {} : { body: serialized }),
      })
    } catch (error) {
      if (options.signal?.aborted || isAbortError(error)) throw error
      throw new ApiError('NETWORK_ERROR', 0, '无法连接本地服务，请确认服务已启动')
    }
    let payload: unknown
    try { payload = await response.json() }
    catch (error) {
      if (options.signal?.aborted || isAbortError(error)) throw error
      throw new ApiError('INVALID_RESPONSE', response.status, '服务器响应格式不正确', [],
        response.headers.get('X-Trace-Id'), response.headers.get('Retry-After'))
    }
    const retryAfter = response.headers.get('Retry-After')
    if (!envelope(payload)) {
      throw new ApiError('INVALID_RESPONSE', response.status, '服务器响应格式不正确', [],
        response.headers.get('X-Trace-Id'), retryAfter)
    }
    if (!response.ok || (payload.code !== 'OK' && payload.code !== 'RUN_IN_PROGRESS')) {
      throw new ApiError(payload.code === 'OK' ? 'INVALID_RESPONSE' : payload.code,
        response.status, payload.code === 'OK' ? '服务器响应格式不正确' : payload.message,
        payload.errors, payload.traceId, retryAfter)
    }
    if (payload.errors.length !== 0 || (payload.code === 'RUN_IN_PROGRESS' && response.status !== 202)) {
      throw new ApiError('INVALID_RESPONSE', response.status, '服务器响应格式不正确', [], payload.traceId)
    }
    return { status: response.status, code: payload.code, data: payload.data as T,
      traceId: payload.traceId, timestamp: payload.timestamp,
      location: response.headers.get('Location'), retryAfter }
  }

  return {
    request,
    get: <T>(path: string, options: RequestOptions = {}) => request<T>(path, { ...options, method: 'GET' }),
    post: <T>(path: string, body?: unknown, options: RequestOptions = {}) => request<T>(path, { ...options, method: 'POST', body }),
    patch: <T>(path: string, body: unknown, options: RequestOptions = {}) => request<T>(path, { ...options, method: 'PATCH', body }),
    delete: <T>(path: string, options: RequestOptions = {}) => request<T>(path, { ...options, method: 'DELETE' }),
  }
}

// Same-origin URLs work through the development proxy and future production reverse proxy.
export const apiClient = createApiClient()
