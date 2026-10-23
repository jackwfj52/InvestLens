import { apiClient } from './apiClient.ts'
import { canonicalDecimal, percentToRatio, ratioToPercent } from './researchInputs.ts'
import type { Snapshot, DecisionVersion, Proposal } from './researchInputs.ts'

export interface InputContext { portfolio?: Snapshot; decision?: DecisionVersion; ready: boolean }
export interface Candidate { instrument: { instrumentId: string; symbol: string; provider: string; proxyFor: string | null }; qualification: string; mainlandAccessVerified: boolean; permissions: { localDisplay: string; modelInput: string; storagePolicy: string } }
export interface Evidence { evidenceId: string; provider: string; sourceUrl: string | null; instrumentId: string | null; asOf: string | null; retrievedAt: string | null; quality: string; dataMode: string; storagePolicy: string; payloadHash: string | null }
export interface CalculationSnapshot { snapshotId: string; portfolioRef: { snapshotId: string; version: number; contentHash: string }; dataMode: string; inputHash: string | null; replayable: boolean; marketInputs: { instrumentId: string; evidenceRef: string }[]; gaps: { code: string; message: string }[] }
export interface Metric { metricId: string; name: string; status: 'CALCULABLE' | 'NOT_CALCULABLE'; value: string | null; unit: string; currency: string | null; formulaVersion: string; inputHash: string; sampleCount: number; sampleWindow: { start: string; end: string; interval: string } | null; reason: string | null; evidenceRefs: string[]; parameters: { minimumSamples: number | null; annualizationFactor: number | null } }
export interface Bundle { bundleId: string; calculationSnapshotId: string; dataMode: string; inputHash: string; createdAt: string; metrics: Metric[]; assets: { instrumentId: string; metricRefs: string[] }[] }
export interface Scenario { scenarioId: string; calculationSnapshotId: string; portfolioRef: CalculationSnapshot['portfolioRef']; decisionRef: { decisionId: string; version: number }; inputHash: string; dataMode: string; createdAt: string; pnlUnit: string; currency: string; sourceEvidenceRef: string; action: Proposal['action']; constraints: Proposal['constraints']; beforeWeights: { instrumentId: string; weight: string }[]; afterWeights: { instrumentId: string; weight: string }[]; shocks: { instrumentId: string; shock: string }[]; impacts: { instrumentId: string; pnlBefore: string; pnlAfter: string }[]; metrics: Metric[]; ruleResults: { ruleId: string; status: string; threshold: string; unit: string; reason: string | null }[]; assumptions: string[]; concentration: { hhiBefore: string; hhiAfter: string; includesCash: boolean; unit: string; formulaVersion: string } }
const path = (kind: string, id: string) => `/${kind}/${encodeURIComponent(id)}`
export const riskApi = {
  candidates: (signal?: AbortSignal) => apiClient.get<Candidate[]>('/market/instruments', { signal }),
  freeze: (portfolioSnapshotId: string, marketInputs: { instrumentId: string; start: string; end: string }[], signal?: AbortSignal) => apiClient.post<CalculationSnapshot>('/calculation-snapshots', { schemaVersion: '1.0.0', portfolioSnapshotId, marketInputs }, { signal }),
  snapshot: (id: string, signal?: AbortSignal) => apiClient.get<CalculationSnapshot>(path('calculation-snapshots', id), { signal }),
  calculate: (calculationSnapshotId: string, signal?: AbortSignal) => apiClient.post<Bundle>('/metric-bundles', { schemaVersion: '1.0.0', calculationSnapshotId }, { signal }),
  bundle: (id: string, signal?: AbortSignal) => apiClient.get<Bundle>(path('metric-bundles', id), { signal }),
  evidence: (id: string, signal?: AbortSignal) => apiClient.get<Evidence>(path('evidence', id), { signal }),
  scenario: (body: { decisionId: string; decisionVersion: number; calculationSnapshotId: string; portfolioSnapshotId: string; shocks: { instrumentId: string; shock: string }[] }, signal?: AbortSignal) => apiClient.post<Scenario>('/scenarios', { schemaVersion: '1.0.0', ...body }, { signal }),
  savedScenario: (id: string, signal?: AbortSignal) => apiClient.get<Scenario>(path('scenarios', id), { signal }),
}
export function eligible(candidate: Candidate | undefined): boolean {
  return !!candidate && candidate.qualification === 'QUALIFIED' && candidate.mainlandAccessVerified
    && candidate.permissions.localDisplay === 'ALLOWED' && candidate.permissions.modelInput === 'ALLOWED'
}
export function shockRatio(value: string): string {
  const negative = value.startsWith('-'), magnitude = negative ? value.slice(1) : value
  const ratio = percentToRatio(magnitude)
  return negative && ratio !== '0' ? `-${ratio}` : ratio
}
export function shockPercent(value: string): string { return value.startsWith('-') ? `-${ratioToPercent(value.slice(1))}` : ratioToPercent(value) }
export function buildShocks(portfolio: Snapshot, values: Record<string, string>) {
  return portfolio.positions.map(p => {
    const value = values[p.instrumentId]
    if (value === undefined || value.trim() === '') throw new Error(`请填写 ${p.instrumentId} 的冲击，现金也必须显式填写`)
    return { instrumentId: p.instrumentId, shock: shockRatio(value) }
  })
}
/** Exact HALF_UP display formatting only; financial results always come from the backend. */
export function displayNumber(value: string | null, unit: string): string {
  if (value === null) return '不可计算'
  const negative = value.startsWith('-'), absolute = negative ? value.slice(1) : value
  canonicalDecimal(absolute)
  const [whole = '', fraction = ''] = absolute.split('.')
  const raw = BigInt(whole + fraction.padEnd(10, '0'))
  const divisor = unit === 'RATIO' ? 1000000n : 100000000n
  const rounded = (raw + divisor / 2n) / divisor
  return `${negative && rounded !== 0n ? '-' : ''}${rounded / 100n}.${(rounded % 100n).toString().padStart(2, '0')}${unit === 'RATIO' ? '%' : unit === 'PERCENTAGE_POINT' ? ' 个百分点' : ''}`
}
export function safeLink(value: string | null): string | undefined {
  try { const url = new URL(value ?? ''); return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined } catch { return undefined }
}
export function sameScenarioContext(result: Scenario, context: InputContext): boolean {
  return result.portfolioRef.snapshotId === context.portfolio?.snapshotId && result.decisionRef.decisionId === context.decision?.decisionId
    && result.decisionRef.version === context.decision?.version
}
