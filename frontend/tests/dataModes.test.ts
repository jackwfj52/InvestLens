import { test } from 'node:test'
import assert from 'node:assert/strict'
import { describeMode } from '../src/services/dataModes.ts'
test('all four modes have explicit provenance and unknown modes never become live', () => {
  for (const mode of ['LIVE', 'CACHE', 'REPLAY', 'SYNTHETIC']) assert.ok(describeMode(mode).label.includes(mode))
  assert.ok(describeMode('SYNTHETIC').hint.includes('不代表真实行情'))
  assert.ok(describeMode('REPLAY').hint.includes('不会更新价格'))
  assert.ok(describeMode('CACHE').hint.includes('不自动请求实时源'))
  assert.ok(describeMode('unknown').hint.includes('不推断为实时'))
})
