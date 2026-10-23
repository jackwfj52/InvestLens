import test from 'node:test'
import assert from 'node:assert/strict'
import { canonicalDecimal, percentToRatio, ratioToPercent, weightsSumToOne } from '../src/services/researchInputs.ts'

test('percentage values preserve decimal precision and differ from percentage points', () => {
  assert.equal(percentToRatio('10'), '0.1')
  assert.equal(canonicalDecimal('10'), '10')
  assert.equal(percentToRatio('000.00000001'), '0.0000000001')
  assert.equal(ratioToPercent('0.1234567891'), '12.34567891')
  assert.equal(percentToRatio('100.0000000000'), '1')
  for (const value of ['-1', '1e1', '', 'NaN', '100.1', '0.000000001']) assert.throws(() => percentToRatio(value))
})
test('weights require an exact 100 percent without float tolerance', () => {
  const position = (weight: string) => ({ weight, quoteCurrency: 'USD', instrumentId: 'CASH:USD' })
  assert.equal(weightsSumToOne(['0.1', '0.2', '0.7'].map(position)), true)
  assert.equal(weightsSumToOne(['0.9999999999'].map(position)), false)
  assert.equal(weightsSumToOne(['0.3333333333', '0.3333333333', '0.3333333334'].map(position)), true)
})
