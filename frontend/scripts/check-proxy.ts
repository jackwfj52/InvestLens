/** Requires real Vite + backend. Read-only / invalid-input checks never create domain rows. */
import assert from 'node:assert/strict'
import { ApiError, createApiClient } from '../src/services/apiClient.ts'

const origin = process.env.FRONTEND_ORIGIN || 'http://127.0.0.1:5173'
const target = new URL(origin)
assert.ok(target.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(target.hostname), 'Use a local dev origin')
const page = await fetch(`${origin}/workbench`)
assert.equal(page.status, 200)
assert.match(await page.text(), /<div id="app"><\/div>/)
const readiness = await fetch(`${origin}/actuator/health/readiness`)
assert.equal(readiness.status, 200)
assert.deepEqual(await readiness.json(), { status: 'UP' })

const client = createApiClient(`${origin}/api`)
await assert.rejects(client.get('/decisions/g07_proxy_probe', { headers: { 'X-G07-Probe': 'safe-local-check' } }), error => {
  assert.ok(error instanceof ApiError)
  assert.equal(error.status, 404)
  assert.equal(error.code, 'RESOURCE_NOT_FOUND')
  assert.match(error.traceId ?? '', /^[a-f0-9-]{36}$/)
  return true
})
await assert.rejects(client.post('/decisions', {
  schemaVersion: '1.0.0', draftId: 'g07_proxy_probe', inputText: '',
  inputStatus: 'NEEDS_INPUT', missingFields: [], proposedDecision: null,
}), error => {
  assert.ok(error instanceof ApiError)
  assert.equal(error.status, 400)
  assert.equal(error.code, 'VALIDATION_ERROR')
  assert.ok(error.errors.length > 0)
  return true
})
console.log('Real dev proxy passed: SPA refresh, readiness UP, backend 404/400 envelopes, field errors and trace IDs')
