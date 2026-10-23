import { apiClient } from './apiClient.ts'
export interface RunEvent { schemaVersion: '1.0.0'; eventId: string; runId: string; sequence: number; type: string; stage: string | null; status: string; createdAt: string; message: string }
export interface EventPage { items: RunEvent[]; nextSequence: number }
export const progressApi = { events: (id: string, after = 0, signal?: AbortSignal) => apiClient.get<EventPage>(`/runs/${encodeURIComponent(id)}/events?afterSequence=${after}`, { signal }) }
export function openProgress(id: string, receive: (event: RunEvent) => void, disconnected: () => void) {
  const source = new EventSource(`/api/runs/${encodeURIComponent(id)}/events`)
  source.addEventListener('progress', event => {
    try { const value = JSON.parse((event as MessageEvent<string>).data) as RunEvent; if (value.runId === id) receive(value); if (value.type === 'RUN_TERMINATED') source.close() }
    catch { source.close(); disconnected() }
  })
  source.onerror = () => { source.close(); disconnected() }
  return () => source.close()
}
