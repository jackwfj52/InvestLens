import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { ApiError, createApiClient } from '../src/services/apiClient.ts'
import type { Fetcher } from '../src/services/apiClient.ts'
import { deferred } from './deferred.ts'

const traceId = 'b2372090-1b41-4e66-a4b0-d0b8d5bac7a6'
function body(code = 'OK', data: unknown = { version: 1 }) {
  return { schemaVersion: '1.0.0', code, message: code === 'OK' ? '请求成功' : '版本已变化',
    data, errors: [], traceId, timestamp: '2026-10-23T00:00:00.000Z' }
}
function response(status = 200, code = 'OK', data: unknown = { version: 1 }, headers: HeadersInit = {}) {
  return new Response(JSON.stringify(body(code, data)), { status, headers })
}

for (const [name, headers] of [
  ['object', { Accept: 'application/vnd.investlens+json', 'X-Custom': 'preserved' }],
  ['Headers', new Headers({ accept: 'application/vnd.investlens+json', 'x-custom': 'preserved' })],
  ['tuples', [['accept', 'application/vnd.investlens+json'], ['x-custom', 'preserved']]],
] as const) {
  test(`merges ${name} headers without overwriting caller values`, async () => {
    const client = createApiClient('/api', async (url, init) => {
      assert.equal(url, '/api/decisions')
      const sent = new Headers(init?.headers)
      assert.equal(sent.get('accept'), 'application/vnd.investlens+json')
      assert.equal(sent.get('x-custom'), 'preserved')
      assert.equal(sent.get('content-type'), 'application/json')
      assert.equal(sent.get('idempotency-key'), 'run_key.1:retry')
      assert.equal(init?.body, '{"value":"0.3"}')
      assert.equal(init?.method, 'POST')
      return response(201, 'OK', { id: 'one' }, { Location: '/api/decisions/one?version=1' })
    })
    const result = await client.post<{ id: string }>('/decisions', { value: '0.3' }, {
      headers: headers as HeadersInit, idempotencyKey: 'run_key.1:retry',
    })
    assert.equal(result.status, 201)
    assert.equal(result.location, '/api/decisions/one?version=1')
    assert.equal(result.traceId, traceId)
    assert.equal(result.data.id, 'one')
  })
}

test('GET sends no JSON content type and custom content type is retained', async () => {
  const client = createApiClient('/api/', async (_url, init) => {
    assert.equal(new Headers(init?.headers).has('Content-Type'), false)
    assert.equal(init?.body, undefined)
    return response()
  })
  await client.get('/decisions/one?version=1')
  const custom = createApiClient('/api', async (_url, init) => {
    assert.equal(new Headers(init?.headers).get('Content-Type'), 'application/json; charset=utf-8')
    return response()
  })
  await custom.patch('/decisions/one', {}, { headers: { 'content-type': 'application/json; charset=utf-8' } })
})

test('invalid JSON bodies fail locally rather than masquerading as a network outage', async () => {
  let calls = 0
  const client = createApiClient('/api', async () => { calls++; return response() })
  const circular: Record<string, unknown> = {}
  circular.self = circular
  for (const value of [circular, 1n, Symbol('invalid')]) {
    await assert.rejects(client.post('/decisions', value), { code: 'INVALID_REQUEST', status: 0 })
  }
  assert.equal(calls, 0)
})

for (const code of ['OK', 'RUN_IN_PROGRESS']) {
  test(`202 ${code} preserves asynchronous semantics and retry metadata`, async () => {
    const result = await createApiClient('/api', async () => response(202, code, { status: 'QUEUED' }, {
      Location: '/api/runs/one', 'Retry-After': '3',
    })).post('/decisions/one/runs', {}, { idempotencyKey: 'same-key' })
    assert.equal(result.status, 202)
    assert.equal(result.code, code)
    assert.equal(result.location, '/api/runs/one')
    assert.equal(result.retryAfter, '3')
    assert.deepEqual(result.data, { status: 'QUEUED' })
  })
}

test('409 exposes safe field errors, HTTP status and trace without arbitrary error data', async () => {
  const payload = { ...body('VERSION_CONFLICT', null), errors: [{ field: 'expectedVersion', code: 'INVALID_VALUE', message: '字段值不符合约束' }] }
  const client = createApiClient('/api', async () => new Response(JSON.stringify(payload), { status: 409 }))
  await assert.rejects(client.patch('/decisions/one', { expectedVersion: 1 }), error => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.code, 'VERSION_CONFLICT')
    assert.equal(error.status, 409)
    assert.equal(error.traceId, traceId)
    assert.equal(error.errors[0]?.field, 'expectedVersion')
    assert.equal(error.message, '版本已变化')
    assert.equal('data' in error, false)
    return true
  })
})

test('HTTP errors and 200 business failures are never returned as success', async () => {
  for (const status of [200, 400, 404, 422, 429, 503]) {
    let calls = 0
    const client = createApiClient('/api', async () => {
      calls++
      return response(status, 'RATE_LIMITED', null, { 'Retry-After': '7' })
    })
    await assert.rejects(client.post('/decisions', {}), error => {
      assert.ok(error instanceof ApiError)
      assert.equal(error.status, status)
      assert.equal(error.code, 'RATE_LIMITED')
      assert.equal(error.retryAfter, '7')
      return true
    })
    assert.equal(calls, 1, 'No automatic retry for writes or rate limits')
  }
})

test('malformed and unsupported envelopes use fixed errors without raw response details', async () => {
  for (const payload of [
    '<html>secret-upstream-detail</html>', JSON.stringify({ message: 'secret-upstream-detail' }),
    JSON.stringify({ ...body(), schemaVersion: '9.0.0', message: 'secret-upstream-detail' }),
    JSON.stringify({ ...body(), traceId: null }), JSON.stringify({ ...body(), errors: [{ field: 1 }] }),
  ]) {
    const client = createApiClient('/api', async () => new Response(payload, { status: 502, headers: { 'X-Trace-Id': traceId } }))
    await assert.rejects(client.get('/decisions/one'), error => {
      assert.ok(error instanceof ApiError)
      assert.equal(error.status, 502)
      assert.equal(error.code, 'INVALID_RESPONSE')
      assert.equal(error.traceId, traceId)
      assert.doesNotMatch(error.message, /secret-upstream-detail/)
      return true
    })
  }
})

test('in-progress on HTTP 200 and successful error lists are protocol errors', async () => {
  await assert.rejects(createApiClient('/api', async () => response(200, 'RUN_IN_PROGRESS')).get('/runs/one'),
    { code: 'INVALID_RESPONSE', status: 200 })
  const payload = { ...body(), errors: [{ field: 'x', code: 'INVALID_VALUE', message: 'invalid' }] }
  await assert.rejects(createApiClient('/api', async () => new Response(JSON.stringify(payload))).get('/one'),
    { code: 'INVALID_RESPONSE' })
})

test('network failures are safe and AbortSignal is passed through unchanged', async () => {
  await assert.rejects(createApiClient('/api', async () => { throw new Error('secret-connection-detail') }).get('/one'),
    { code: 'NETWORK_ERROR', status: 0, message: '无法连接本地服务，请确认服务已启动' })
  const controller = new AbortController()
  const reason = new DOMException('client wait cancelled', 'AbortError')
  const seen: string[] = []
  const client = createApiClient('/api', async (url, init) => {
    seen.push(String(url))
    assert.equal(init?.signal, controller.signal)
    controller.abort(reason)
    throw reason
  })
  await assert.rejects(client.post('/decisions/one/runs', {}, { signal: controller.signal }), error => error === reason)
  assert.deepEqual(seen, ['/api/decisions/one/runs'], 'Aborting fetch never sends /cancel')
})

test('abort during response body reading remains an abort', async () => {
  const reason = new DOMException('cancelled while reading', 'AbortError')
  const client = createApiClient('/api', async () => new Response(new ReadableStream({
    start(controller) { controller.error(reason) },
  })))
  await assert.rejects(client.get('/one'), error => error === reason)
})

test('idempotency keys stay stable across explicit retries and conflicting keys fail locally', async () => {
  const sent: string[] = []
  const client = createApiClient('/api', async (_url, init) => {
    sent.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
    return response(202)
  })
  await client.post('/decisions/one/runs', {}, { headers: { 'Idempotency-Key': 'caller-key' } })
  await client.post('/decisions/one/runs', {}, { idempotencyKey: 'caller-key' })
  assert.deepEqual(sent, ['caller-key', 'caller-key'])
  for (const options of [{ idempotencyKey: '' }, { idempotencyKey: 'bad key' },
    { idempotencyKey: 'a'.repeat(129) }, { idempotencyKey: 'new', headers: { 'Idempotency-Key': 'old' } }]) {
    await assert.rejects(client.post('/runs', {}, options), { code: 'INVALID_REQUEST', status: 0 })
  }
  assert.equal(sent.length, 2)
})

test('real HTTP abort closes only the browser wait while accepted server work continues', async () => {
  const arrived = deferred<void>()
  const finished = deferred<void>()
  const urls: string[] = []
  const server = createServer((req, res) => {
    urls.push(req.url ?? '')
    arrived.resolve()
    setTimeout(() => { res.end(JSON.stringify(body('OK', { status: 'QUEUED' }))); finished.resolve() }, 80)
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const controller = new AbortController()
  try {
    const pending = createApiClient(`http://127.0.0.1:${address.port}/api`, fetch as Fetcher)
      .post('/decisions/one/runs', {}, { idempotencyKey: 'one-key', signal: controller.signal })
    const rejection = assert.rejects(pending, { name: 'AbortError' })
    await arrived.promise
    controller.abort()
    await rejection
    await finished.promise
    assert.deepEqual(urls, ['/api/decisions/one/runs'])
  } finally {
    server.closeAllConnections()
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  }
})
