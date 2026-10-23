import { apiClient } from './apiClient.ts'

export const assets = [
  { id: 'ETF:SYNTHETIC:DEMO_GOLD:USD', label: '黄金 ETF 代理（合成）' },
  { id: 'ETF:SYNTHETIC:DEMO_SILVER:USD', label: '白银 ETF 代理（合成）' },
  { id: 'STOCK:SYNTHETIC:DEMO_STOCK:USD', label: '股票（合成）' },
  { id: 'CASH:USD', label: '美元现金' },
] as const
export type AllocationMode = 'TARGET_WEIGHT' | 'DELTA_PERCENTAGE_POINTS'
export interface Position { instrumentId: string; quoteCurrency: string; weight: string }
export interface Snapshot {
  schemaVersion: string; snapshotId: string; portfolioId: string; version: number
  baseCurrency: string; valuationBasis: 'NORMALIZED' | 'ABSOLUTE'; totalValue: string
  asOf: string; dataMode: 'SYNTHETIC' | 'LIVE' | 'CACHE' | 'REPLAY'; positions: Position[]; contentHash: string
}
export interface Proposal {
  baseCurrency?: string; portfolioSnapshotId?: string
  action?: { type: 'REALLOCATE'; instrumentId: string; fundingInstrumentId: string
    allocationMode: AllocationMode; targetWeight?: string; deltaPercentagePoints?: string }
  horizon?: { value: number; unit: 'DAY' | 'MONTH' | 'YEAR' }
  constraints?: { maxTargetWeight: string; maxScenarioLoss: string }
  hypotheses?: { schemaVersion: string; hypothesisId: string; statement: string
    mechanism: string; testMethod: string; supportStatus: 'UNVERIFIED' }[]
}
export interface DecisionVersion extends Proposal {
  schemaVersion: string; decisionId: string; version: number; inputText: string
  inputStatus: 'NEEDS_INPUT' | 'READY' | 'CONFIRMED'; confirmedAt: string | null
  missingFields: { field: string; code: string; message: string }[]
}
export interface Draft {
  schemaVersion: string; draftId: string; inputText: string
  inputStatus: 'NEEDS_INPUT' | 'READY'; missingFields: { field: string; code: string; message: string }[]
  proposedDecision: Proposal
}

/** Decimal strings only: converting 10% to 0.1 must not introduce binary rounding. */
export function canonicalDecimal(value: string): string {
  if (!/^\d{1,18}(?:\.\d{1,10})?$/.test(value)) throw new Error('请输入最多 10 位小数的非负十进制数')
  const [whole = '', fraction = ''] = value.split('.')
  const result = `${BigInt(whole)}${fraction.replace(/0+$/, '') ? `.${fraction.replace(/0+$/, '')}` : ''}`
  return result
}
export function percentToRatio(value: string): string {
  const normalized = canonicalDecimal(value)
  const [whole = '', fraction = ''] = normalized.split('.')
  const digits = (whole + fraction).padStart(fraction.length + 3, '0')
  const split = digits.length - fraction.length - 2
  const result = canonicalDecimal(`${digits.slice(0, split)}.${digits.slice(split)}`)
  if (compareDecimal(result, '1') > 0) throw new Error('百分比必须介于 0% 和 100%')
  return result
}
export function ratioToPercent(value: string): string {
  const [whole = '', fraction = ''] = canonicalDecimal(value).split('.')
  const padded = fraction.padEnd(2, '0')
  return canonicalDecimal(`${BigInt(whole + padded.slice(0, 2))}${padded.slice(2) ? `.${padded.slice(2)}` : ''}`)
}
function units(value: string): bigint {
  const [whole = '', fraction = ''] = value.split('.')
  return BigInt(whole + fraction.padEnd(10, '0'))
}
export function compareDecimal(a: string, b: string): number { return units(a) > units(b) ? 1 : units(a) < units(b) ? -1 : 0 }
export function weightsSumToOne(positions: Position[]): boolean {
  return positions.reduce((sum, position) => sum + units(position.weight), 0n) === 10000000000n
}
const idPath = (kind: string, id: string, version?: number) => `/${kind}/${encodeURIComponent(id)}${version ? `?version=${version}` : ''}`
export const inputApi = {
  portfolio: (id: string, version?: number) => apiClient.get<Snapshot>(idPath('portfolios', id, version)),
  decision: (id: string) => apiClient.get<DecisionVersion>(idPath('decisions', id)),
  createPortfolio: (body: unknown) => apiClient.post<Snapshot>('/portfolios', body),
  revisePortfolio: (id: string, body: unknown) => apiClient.patch<Snapshot>(idPath('portfolios', id), body),
  saveDecision: (draft: Draft, current?: DecisionVersion, confirm = false) => current
    ? apiClient.patch<DecisionVersion>(idPath('decisions', current.decisionId), { schemaVersion: '1.0.0', expectedVersion: current.version, draft, confirm })
    : apiClient.post<DecisionVersion>('/decisions', draft),
}
