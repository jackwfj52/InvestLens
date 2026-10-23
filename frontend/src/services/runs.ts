import { apiClient } from './apiClient.ts'
import type { InputContext } from './riskResearch.ts'
export interface CreateRun { schemaVersion: '1.0.0'; decisionVersion: number; portfolioSnapshotId: string; portfolioVersion: number; dataMode: string }
export interface Run { runId: string; decisionId: string; decisionVersion: number; portfolioSnapshotId: string; portfolioVersion: number; dataMode: string; status: string; stage: string | null; errorCode: string | null; reportId: string | null; budget: { maxDataAttempts: number; maxModelAttempts: number; dataAttempts: number; modelAttempts: number; deadlineAt: string } }
export interface RunIntent { decisionId: string; key: string; request: CreateRun }
const storageKey = 'investlens.run-intent'
export function runIntent(context: InputContext, previous?: RunIntent, newKey: () => string = () => crypto.randomUUID()): RunIntent {
  const { decision, portfolio, ready } = context
  if (!ready || !decision || !portfolio || decision.inputStatus !== 'CONFIRMED' || decision.portfolioSnapshotId !== portfolio.snapshotId) throw new Error('请先保存并确认匹配的输入版本')
  const request: CreateRun = { schemaVersion: '1.0.0', decisionVersion: decision.version, portfolioSnapshotId: portfolio.snapshotId, portfolioVersion: portfolio.version, dataMode: portfolio.dataMode }
  if (previous?.decisionId === decision.decisionId && JSON.stringify(previous.request) === JSON.stringify(request)) return previous
  return { decisionId: decision.decisionId, key: newKey(), request }
}
export function loadIntent(storage?: Pick<Storage, 'getItem'>): RunIntent | undefined {
  try {
    const value: unknown = JSON.parse((storage ?? localStorage).getItem(storageKey) ?? 'null')
    if (!value || typeof value !== 'object') return
    const v = value as Partial<RunIntent>, r = v.request
    if (typeof v.decisionId === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(v.key ?? '') && r?.schemaVersion === '1.0.0'
      && Number.isInteger(r.decisionVersion) && r.decisionVersion > 0 && Number.isInteger(r.portfolioVersion) && r.portfolioVersion > 0
      && typeof r.portfolioSnapshotId === 'string' && ['LIVE', 'CACHE', 'REPLAY', 'SYNTHETIC'].includes(r.dataMode)) return v as RunIntent
  } catch { /* Storage unavailable or corrupted. */ }
}
export function saveIntent(intent: RunIntent) { try { localStorage.setItem(storageKey, JSON.stringify(intent)) } catch { /* In-memory intent remains reusable. */ } }
export const runApi = {
  create: (intent: RunIntent) => apiClient.post<Run>(`/decisions/${encodeURIComponent(intent.decisionId)}/runs`, intent.request, { idempotencyKey: intent.key }),
  get: (id: string) => apiClient.get<Run>(`/runs/${encodeURIComponent(id)}`),
}
