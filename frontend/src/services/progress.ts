import { apiClient, ApiError } from './apiClient.ts'
export interface RunEvent { schemaVersion: '1.0.0'; eventId: string; runId: string; sequence: number; type: string; stage: string | null; status: string; createdAt: string; message: string }
export interface EventPage { items: RunEvent[]; nextSequence: number }
export const progressApi = { events: (id: string, after = 0, signal?: AbortSignal) => apiClient.get<EventPage>(`/runs/${encodeURIComponent(id)}/events?afterSequence=${after}`, { signal }) }
export type ProgressMode = 'CONNECTING' | 'LIVE' | 'POLLING' | 'FINISHED'
type StorageLike = Pick<Storage, 'getItem' | 'setItem'>
type StreamCallbacks = { event: (value: unknown) => void; open: () => void; failed: () => void }
export interface ProgressOptions {
  storage?: StorageLike
  read?: (id: string, after: number, signal: AbortSignal) => Promise<EventPage>
  connect?: (id: string, after: number, callbacks: StreamCallbacks) => () => void
  pollMillis?: number
  reconnectMillis?: number
}
export function validEvent(value: unknown, id: string): value is RunEvent {
  if (!value || typeof value !== 'object') return false
  const event = value as RunEvent
  return event.schemaVersion === '1.0.0' && event.runId === id && Number.isSafeInteger(event.sequence) && event.sequence > 0
    && event.eventId === `${id}:${event.sequence}` && ['RUN_QUEUED', 'STEP_STARTED', 'STEP_SUCCEEDED', 'STEP_FAILED', 'RUN_TERMINATED'].includes(event.type)
    && ['CREATED', 'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(event.status)
    && (event.stage === null || ['PLAN', 'RESEARCH', 'QUANT', 'DEBATE', 'JUDGE', 'VALIDATE'].includes(event.stage))
    && typeof event.message === 'string' && typeof event.createdAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(event.createdAt)
    && (event.type !== 'RUN_TERMINATED' || ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(event.status))
}
function browserStorage(): StorageLike | undefined { try { return localStorage } catch { return undefined } }
function connectBrowser(id: string, after: number, callbacks: StreamCallbacks) {
  const source = new EventSource(`/api/runs/${encodeURIComponent(id)}/events?afterSequence=${after}`)
  source.onopen = callbacks.open
  source.addEventListener('progress', event => {
    try { const message = event as MessageEvent<string>, value: unknown = JSON.parse(message.data)
      if (!validEvent(value, id) || message.lastEventId !== value.eventId) throw new Error('Invalid event')
      callbacks.event(value)
    } catch { callbacks.failed() }
  })
  source.onerror = callbacks.failed
  return () => source.close()
}
export function subscribeProgress(id: string, receive: (event: RunEvent) => void, mode: (state: ProgressMode) => void, finished: () => void, options: ProgressOptions = {}) {
  const storage = options.storage ?? browserStorage(), key = `investlens.progress.${id}`, controller = new AbortController()
  const read = options.read ?? (async (runId, after, signal) => (await progressApi.events(runId, after, signal)).data)
  const connect = options.connect ?? connectBrowser
  let cursor = 0, trail: RunEvent[] = [], stopped = false, reading = false, streamVersion = 0, pollVersion = 0, failures = 0, reset = false
  let close: (() => void) | undefined, pollTimer: ReturnType<typeof setTimeout> | undefined, retryTimer: ReturnType<typeof setTimeout> | undefined
  function persist() { try { storage?.setItem(key, JSON.stringify({ cursor, items: trail })) } catch { /* Continue with the in-memory cursor. */ } }
  function closeStream() { ++streamVersion; close?.(); close = undefined }
  function stop() { stopped = true; ++pollVersion; controller.abort(); closeStream(); clearTimeout(pollTimer); clearTimeout(retryTimer) }
  function accept(value: unknown) {
    if (stopped) return
    if (!validEvent(value, id)) throw new Error('Invalid event')
    if (value.sequence <= cursor) return
    if (value.sequence !== cursor + 1) throw new Error('Event gap')
    receive(value); cursor = value.sequence; trail = [...trail, value].slice(-100); persist()
    if (value.type === 'RUN_TERMINATED') { stop(); mode('FINISHED'); finished() }
  }
  async function catchup() {
    if (stopped || reading) return
    reading = true
    try {
      let more = true
      while (!stopped && more) {
        const after = cursor, page = await read(id, after, controller.signal)
        if (stopped) return
        if (!Array.isArray(page.items) || page.items.length > 100 || !Number.isSafeInteger(page.nextSequence)
          || page.nextSequence !== (page.items.length ? page.items[page.items.length - 1]!.sequence : after)) throw new Error('Invalid event page')
        for (const event of page.items) accept(event)
        more = page.items.length === 100
      }
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'INVALID_REQUEST' && !reset) { reset = true; cursor = 0; trail = []; persist() }
      throw failure
    } finally { reading = false }
  }
  async function poll(version: number) {
    if (stopped || version !== pollVersion) return
    try { await catchup() } catch { if (!stopped) mode('POLLING') }
    if (!stopped && version === pollVersion) pollTimer = setTimeout(() => void poll(version), options.pollMillis ?? 2000)
  }
  function fallback() {
    if (stopped) return
    closeStream(); mode('POLLING'); clearTimeout(pollTimer); clearTimeout(retryTimer)
    void poll(++pollVersion)
    retryTimer = setTimeout(open, Math.min((options.reconnectMillis ?? 1000) * 2 ** Math.min(failures++, 4), 15000))
  }
  function open() {
    if (stopped) return
    closeStream(); const version = streamVersion
    try {
      const release = connect(id, cursor, {
        open: () => { if (!stopped && version === streamVersion) { failures = 0; ++pollVersion; clearTimeout(pollTimer); mode('LIVE') } },
        event: value => { if (!stopped && version === streamVersion) try { accept(value) } catch { fallback() } },
        failed: () => { if (!stopped && version === streamVersion) fallback() },
      })
      if (stopped || version !== streamVersion) release(); else close = release
    } catch { fallback() }
  }
  mode('CONNECTING')
  try {
    const saved: unknown = JSON.parse(storage?.getItem(key) ?? 'null')
    if (saved && typeof saved === 'object') {
      const item = saved as { cursor: number; items: unknown[] }
      if (Array.isArray(item.items) && item.items.length > 0 && item.items.length <= 100 && item.items.every(value => validEvent(value, id))
        && Number.isSafeInteger(item.cursor) && item.cursor === (item.items[item.items.length - 1] as RunEvent).sequence
        && item.items.every((value, index) => index === 0 || (value as RunEvent).sequence === (item.items[index - 1] as RunEvent).sequence + 1)) {
        trail = item.items as RunEvent[]; cursor = item.cursor; trail.forEach(receive)
      }
    }
  } catch { /* Invalid or inaccessible storage starts from the first event. */ }
  void (async () => {
    if (trail[trail.length - 1]?.type === 'RUN_TERMINATED') { stop(); mode('FINISHED'); finished(); return }
    try { await catchup(); open() } catch { fallback() }
  })()
  return stop
}
