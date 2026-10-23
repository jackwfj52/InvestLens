import { test } from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { useAsyncRequest } from '../src/composables/useAsyncRequest.ts'
import { ApiError } from '../src/services/apiClient.ts'
import { checkBackend } from '../src/services/backendStatus.ts'
import { deferred } from './deferred.ts'

test('latest request wins even when a previous loader ignores AbortSignal', async () => {
  const scope = effectScope()
  const old = deferred<string>(), current = deferred<string>()
  const signals: AbortSignal[] = []
  let index = 0
  const task = scope.run(() => useAsyncRequest(signal => {
    signals.push(signal)
    return index++ === 0 ? old.promise : current.promise
  }))!
  try {
    const first = task.execute()
    assert.equal(task.phase.value, 'loading')
    const second = task.execute()
    assert.equal(signals[0]?.aborted, true)
    current.resolve('current')
    await second
    old.resolve('outdated')
    await first
    assert.equal(task.data.value, 'current')
    assert.equal(task.phase.value, 'success')
  } finally { scope.stop() }
})

test('cancel and scope disposal abort active waits without creating an error', async () => {
  const scope = effectScope()
  const completion = deferred<string>()
  const signals: AbortSignal[] = []
  const task = scope.run(() => useAsyncRequest(signal => { signals.push(signal); return completion.promise }))!
  const pending = task.execute()
  task.cancel()
  assert.equal(signals[0]?.aborted, true)
  assert.equal(task.phase.value, 'idle')
  assert.equal(task.error.value, null)
  completion.resolve('late')
  await pending
  assert.equal(task.data.value, null)
  const next = task.execute()
  scope.stop()
  await next
  assert.equal(signals[1]?.aborted, true)
  assert.equal(task.phase.value, 'idle')
})

test('safe error, retry loading and success states remain distinguishable', async () => {
  const scope = effectScope()
  let calls = 0
  const task = scope.run(() => useAsyncRequest(async () => {
    if (calls++ === 0) throw new ApiError('PERSISTENCE_UNAVAILABLE', 503, '持久化服务暂不可用')
    return 'ready'
  }))!
  try {
    await task.execute()
    assert.equal(task.phase.value, 'error')
    assert.equal(task.error.value?.status, 503)
    const pending = task.execute()
    assert.equal(task.phase.value, 'loading')
    assert.equal(task.error.value, null)
    await pending
    assert.equal(task.phase.value, 'success')
    assert.equal(task.data.value, 'ready')
  } finally { scope.stop() }
})

test('unexpected errors are not displayed verbatim', async () => {
  const scope = effectScope()
  const task = scope.run(() => useAsyncRequest(async () => { throw new Error('secret-diagnostic') }))!
  try {
    await task.execute()
    assert.equal(task.error.value?.message, '请求暂时不可用，请稍后重试')
  } finally { scope.stop() }
})

test('readiness uses Actuator shape and handles DOWN, network and cancellation', async () => {
  const controller = new AbortController()
  await checkBackend(controller.signal, async (url, init) => {
    assert.equal(url, '/actuator/health/readiness')
    assert.equal(init?.signal, controller.signal)
    return new Response('{"status":"UP"}')
  })
  await assert.rejects(checkBackend(controller.signal, async () => new Response('{"status":"DOWN"}', { status: 503 })),
    { code: 'BACKEND_NOT_READY', status: 503 })
  await assert.rejects(checkBackend(controller.signal, async () => { throw new Error('secret-network') }),
    { code: 'NETWORK_ERROR' })
  const reason = new DOMException('cancelled', 'AbortError')
  await assert.rejects(checkBackend(controller.signal, async () => { throw reason }), error => error === reason)
})
