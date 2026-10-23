import { test } from 'node:test'
import assert from 'node:assert/strict'
import { referenceId, reportMatches, terminal } from '../src/services/reports.ts'
import type { Report } from '../src/services/reports.ts'
import type { Run } from '../src/services/runs.ts'
import { runIntent } from '../src/services/runs.ts'
import type { InputContext } from '../src/services/riskResearch.ts'
test('report reopening checks original fixed versions and isolates reference anchors by run', () => {
  const run = { runId: 'run1', reportId: 'r', decisionId: 'd', decisionVersion: 2, portfolioSnapshotId: 'p', portfolioVersion: 1, dataMode: 'REPLAY' } as Run
  const report = { reportId: 'r', runId: 'run1', decisionRef: { decisionId: 'd', version: 2 }, portfolioRef: { snapshotId: 'p', version: 1 }, dataMode: 'REPLAY' } as Report
  assert.ok(reportMatches(report, run)); assert.ok(!reportMatches({ ...report, runId: 'other' }, run))
  assert.ok(!reportMatches({ ...report, decisionRef: { decisionId: 'd', version: 3 } }, run))
  assert.notEqual(referenceId('run1', 'e'), referenceId('run2', 'e'))
  assert.notEqual(referenceId('run-a', 'b'), referenceId('run', 'a-b'))
  assert.ok(terminal('PARTIAL')); assert.ok(!terminal('RUNNING'))
})
test('changing explicit scenario creates a separate run intent without mutating the previous one', () => {
  const context = { ready: true, decision: { decisionId: 'd', version: 1, inputStatus: 'CONFIRMED', portfolioSnapshotId: 'p' }, portfolio: { snapshotId: 'p', version: 1, dataMode: 'SYNTHETIC' } } as InputContext
  const first = runIntent(context, undefined, () => 'one', 'scenario1')
  assert.equal(runIntent(context, first, () => 'two', 'scenario1'), first)
  assert.equal(runIntent(context, first, () => 'two', 'scenario2').key, 'two')
  assert.equal(first.request.scenarioId, 'scenario1')
})
