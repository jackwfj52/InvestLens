import { apiClient } from './apiClient.ts'
import type { Metric, Evidence } from './riskResearch.ts'
import type { Run } from './runs.ts'
export interface Page<T> { items: T[]; nextCursor: string | null }
export interface DecisionSummary { decisionId: string; version: number; inputText: string; inputStatus: string; updatedAt: string; latestRunId: string | null }
export interface Gap { code: string; message: string }
export interface Claim { claimId: string; role: string; relation: string; text: string; evidenceRefs: string[]; metricRefs: string[]; conditions: string[]; unknowns: string[]; invalidationConditions: string[] }
export interface Figure { metricId: string; name: string; displayValue: string; formulaVersion: string; evidenceRefs: string[] }
export interface Report {
  reportId: string; runId: string; snapshotHash: string; researchSnapshotId: string
  decisionRef: { decisionId: string; version: number }; portfolioRef: { snapshotId: string; version: number }
  status: string; dataMode: string; dataQuality: string; generationMode: string; publishedAt: string; validatedAt: string
  scope: { requiredAnalyses: string[]; optionalAnalyses: string[] }; claims: Claim[]
  hypothesisAssessments: { hypothesisId: string; supportStatus: string; reason: string; evidenceRefs: string[]; metricRefs: string[] }[]
  verdict: { verdict: string; thesisAssessment: string; unknowns: string[]; invalidationConditions: string[]; nextChecks: string[] }
  metricBundle: { metrics: Metric[] }
  scenarioResult: { beforeWeights: { instrumentId: string; weight: string }[]; afterWeights: { instrumentId: string; weight: string }[]; ruleResults: { ruleId: string; status: string; threshold: string; unit: string; metricRefs: string[]; reason: string | null }[] }
  roleResults: Record<string, string>; gaps: Gap[]; limitations: string[]
  versions: { engineVersion: string; qualityPolicyVersion: string; modelConfigVersion: string; promptVersions: Record<string, string> }
}
export interface EvidenceBoard { items: (Evidence & { displayId: string; type: string; storagePolicy: string; modelInputAllowed: boolean })[]; gaps: Gap[] }
export const terminal = (status: string) => ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status)
export const referenceId = (runId: string, id: string) => `ref-${runId.length}-${runId}-${id}`
export function reportMatches(report: Report, run: Run) {
  return report.runId === run.runId && report.reportId === run.reportId && report.decisionRef.decisionId === run.decisionId
    && report.decisionRef.version === run.decisionVersion && report.portfolioRef.snapshotId === run.portfolioSnapshotId
    && report.portfolioRef.version === run.portfolioVersion && report.dataMode === run.dataMode
}
export const reportApi = {
  report: (id: string, signal?: AbortSignal) => apiClient.get<Report | Run>(`/runs/${encodeURIComponent(id)}/report`, { signal }),
  evidence: (id: string, signal?: AbortSignal) => apiClient.get<EvidenceBoard>(`/runs/${encodeURIComponent(id)}/evidence`, { signal }),
  figures: (id: string, signal?: AbortSignal) => apiClient.get<Figure[]>(`/runs/${encodeURIComponent(id)}/report/figures`, { signal }),
  decisions: (cursor?: string, signal?: AbortSignal) => apiClient.get<Page<DecisionSummary>>(`/decisions?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { signal }),
  runs: (id: string, cursor?: string, signal?: AbortSignal) => apiClient.get<Page<Run>>(`/decisions/${encodeURIComponent(id)}/runs?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { signal }),
}
