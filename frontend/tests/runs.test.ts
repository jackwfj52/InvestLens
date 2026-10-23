import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadIntent, runIntent } from '../src/services/runs.ts'
import type { InputContext } from '../src/services/riskResearch.ts'
const context = { ready: true, decision: { decisionId: 'd', version: 1, inputStatus: 'CONFIRMED', portfolioSnapshotId: 'p' }, portfolio: { snapshotId: 'p', version: 1, dataMode: 'SYNTHETIC' } } as InputContext
test('one intent keeps its key through retries and refresh; explicit rerun gets a new key', () => {
  const first = runIntent(context, undefined, () => 'key1')
  assert.equal(runIntent(context, first, () => 'key2'), first)
  const restored = loadIntent({ getItem: () => JSON.stringify(first) })
  assert.deepEqual(runIntent(context, restored, () => 'key2'), first)
  assert.equal(runIntent(context, undefined, () => 'key2').key, 'key2')
  assert.equal(runIntent({ ...context, decision: { ...context.decision!, version: 2 } }, first, () => 'key3').key, 'key3')
})
test('unconfirmed or mismatched input cannot start a run; invalid persisted intent is ignored', () => {
  assert.throws(() => runIntent({ ...context, ready: false }))
  assert.throws(() => runIntent({ ...context, decision: { ...context.decision!, portfolioSnapshotId: 'other' } }))
  assert.equal(loadIntent({ getItem: () => '{' }), undefined)
  assert.equal(loadIntent({ getItem: () => '{}' }), undefined)
})
