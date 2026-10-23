import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { displayNumber, shockRatio, shockPercent, buildShocks, eligible, safeLink } from '../src/services/riskResearch.ts'
import { useAsyncRequest } from '../src/composables/useAsyncRequest.ts'
import { deferred } from './deferred.ts'
import type { Snapshot } from '../src/services/researchInputs.ts'

test('backend ratios, percentage points and money display with exact HALF_UP and null never becomes zero', () => {
  assert.equal(displayNumber('-0.065', 'RATIO'), '-6.50%')
  assert.equal(displayNumber('-0.085', 'RATIO'), '-8.50%')
  assert.equal(displayNumber('-2', 'PERCENTAGE_POINT'), '-2.00 个百分点')
  assert.equal(displayNumber('-8.5', 'BASE_CURRENCY_UNIT'), '-8.50')
  assert.equal(displayNumber(null, 'RATIO'), '不可计算')
  assert.equal(displayNumber('0', 'RATIO'), '0.00%')
  assert.equal(displayNumber('-0.00001', 'RATIO'), '0.00%')
  assert.equal(displayNumber('-0.00005', 'RATIO'), '-0.01%')
  assert.equal(displayNumber('999999999999999999.995', 'CURRENCY'), '1000000000000000000.00')
})
test('signed shock percentages preserve precision and enforce full-loss/product boundaries', () => {
  assert.equal(shockRatio('-20'), '-0.2'); assert.equal(shockRatio('100'), '1'); assert.equal(shockRatio('-100'), '-1')
  assert.equal(shockRatio('-0'), '0'); assert.equal(shockRatio('-0.00000001'), '-0.0000000001')
  assert.equal(shockPercent('-0.1234567891'), '-12.34567891')
  for (const input of ['', '1e2', 'NaN', '-100.1', '100.1', '0.000000001']) assert.throws(() => shockRatio(input))
})
test('every holding including cash and zero-weight positions requires an explicit shock', () => {
  const portfolio = { positions: [{ instrumentId: 'GOLD', weight: '0.3' }, { instrumentId: 'CASH:USD', weight: '0' }] } as Snapshot
  assert.throws(() => buildShocks(portfolio, { GOLD: '-20' }), /现金/)
  assert.throws(() => buildShocks(portfolio, { GOLD: '-20', 'CASH:USD': '' }))
  assert.deepEqual(buildShocks(portfolio, { GOLD: '-20', 'CASH:USD': '0' }), [{ instrumentId: 'GOLD', shock: '-0.2' }, { instrumentId: 'CASH:USD', shock: '0' }])
})
test('qualification and source links fail closed without inventing prices', () => {
  const candidate = { qualification: 'QUALIFIED', mainlandAccessVerified: true, permissions: { localDisplay: 'ALLOWED', modelInput: 'ALLOWED', storagePolicy: 'PAYLOAD_ALLOWED' } } as Parameters<typeof eligible>[0]
  assert.equal(eligible(candidate), true)
  assert.equal(eligible({ ...candidate!, qualification: 'PENDING' }), false)
  assert.equal(eligible({ ...candidate!, mainlandAccessVerified: false }), false)
  assert.equal(eligible(undefined), false)
  assert.equal(safeLink('javascript:alert(1)'), undefined); assert.equal(safeLink('https://example.com'), 'https://example.com/')
})
test('editing scenario inputs immediately removes old numbers and rejects a late previous response', async () => {
  const scope = effectScope(), old = deferred<string>(), current = deferred<string>()
  let index = 0
  const task = scope.run(() => useAsyncRequest(() => index++ === 0 ? old.promise : current.promise))!
  try {
    const first = task.execute(); task.reset()
    assert.equal(task.data.value, null); assert.equal(task.phase.value, 'idle')
    const next = task.execute(); current.resolve('new-shock'); await next
    old.resolve('old-shock'); await first
    assert.equal(task.data.value, 'new-shock')
    task.reset(); assert.equal(task.data.value, null); assert.equal(task.error.value, null)
  } finally { scope.stop() }
})
