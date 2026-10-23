import { test } from 'node:test'
import assert from 'node:assert/strict'
import { subscribeProgress, validEvent } from '../src/services/progress.ts'
import type { RunEvent, EventPage, ProgressOptions } from '../src/services/progress.ts'
import { lastRun } from '../src/services/runs.ts'
const event = (sequence: number, type = 'STEP_SUCCEEDED'): RunEvent => ({ schemaVersion: '1.0.0', runId: 'r', eventId: `r:${sequence}`, sequence, type, stage: 'RESEARCH', status: type === 'RUN_TERMINATED' ? 'PARTIAL' : 'RUNNING', createdAt: '2026-10-24T00:00:00.000Z', message: 'saved' })
const flush = () => new Promise<void>(resolve => setImmediate(resolve))
const wait = (millis: number) => new Promise<void>(resolve => setTimeout(resolve, millis))
function memory() { const values = new Map<string, string>(); return { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } } }
function page(rows: RunEvent[], after: number): EventPage { const items = rows.filter(item => item.sequence > after); return { items, nextSequence: items.at(-1)?.sequence ?? after } }
test('catchup, SSE duplicate, gap repair and terminal close preserve a contiguous persisted cursor', async t => {
  const storage = memory(), received: number[] = [], reads: number[] = []; let rows = [event(1)]
  let callbacks: Parameters<NonNullable<ProgressOptions['connect']>>[2] | undefined, closed = 0, finished = 0
  const stop = subscribeProgress('r', value => received.push(value.sequence), () => {}, () => { finished++ }, { storage,
    read: async (_id, after) => { reads.push(after); return page(rows, after) },
    connect: (_id, after, current) => { assert.equal(after, 1); callbacks = current; current.open(); return () => { closed++ } }, pollMillis: 5, reconnectMillis: 10000 })
  t.after(stop); await flush(); assert.deepEqual(received, [1])
  callbacks!.event(event(1)); assert.deepEqual(received, [1])
  rows = [event(1), event(2), event(3, 'RUN_TERMINATED')]; callbacks!.event(event(3, 'RUN_TERMINATED')); await flush()
  assert.deepEqual(received, [1, 2, 3]); assert.equal(finished, 1); assert.ok(closed > 0); assert.ok(reads.includes(1))
  assert.equal(JSON.parse(storage.getItem('investlens.progress.r')!).cursor, 3)
})
test('reload uses the saved run cursor, replays prior trail and never creates a task', async t => {
  const storage = memory(); storage.setItem('investlens.progress.r', JSON.stringify({ cursor: 2, items: [event(1), event(2)] }))
  const seen: number[] = [], reads: number[] = []; let opened = 0, finished = 0
  const stop = subscribeProgress('r', value => seen.push(value.sequence), () => {}, () => { finished++ }, { storage,
    read: async (_id, after) => { reads.push(after); return page([event(3, 'RUN_TERMINATED')], after) }, connect: () => { opened++; return () => {} } })
  t.after(stop); await flush(); assert.deepEqual(reads, [2]); assert.deepEqual(seen, [1, 2, 3]); assert.equal(opened, 0); assert.equal(finished, 1)
  assert.equal(lastRun({ getItem: () => 'r' }), 'r'); assert.equal(lastRun({ getItem: () => '../unsafe' }), undefined)
})
test('offline errors fall back to polling, reconnect and do not accept stale transport events', async t => {
  let attempts = 0, rows: RunEvent[] = [], closed = 0
  const transports: Parameters<NonNullable<ProgressOptions['connect']>>[2][] = [], seen: number[] = [], modes: string[] = []
  const stop = subscribeProgress('r', value => seen.push(value.sequence), state => modes.push(state), () => {}, { storage: memory(),
    read: async (_id, after) => { if (attempts++ < 2) throw new Error('offline'); return page(rows, after) },
    connect: (_id, _after, callbacks) => { transports.push(callbacks); callbacks.open(); return () => { closed++ } }, pollMillis: 3, reconnectMillis: 3 })
  t.after(stop); await wait(25); assert.ok(modes.includes('POLLING')); assert.ok(transports.length > 0)
  const previous = transports[transports.length - 1]!; rows = [event(1)]; previous.failed(); await wait(25)
  previous.event(event(2)); assert.deepEqual(seen, [1]); assert.ok(closed > 0)
  transports[transports.length - 1]!.event(event(2, 'RUN_TERMINATED')); assert.deepEqual(seen, [1, 2])
})
test('switch/dispose aborts pending reads, ignores late responses and corrupt storage starts at zero', async t => {
  let resolve: (value: EventPage) => void = () => {}, signal: AbortSignal | undefined
  const received: number[] = [], storage = memory(); storage.setItem('investlens.progress.r', '{')
  const stop = subscribeProgress('r', value => received.push(value.sequence), () => {}, () => {}, { storage,
    read: (_id, after, active) => { assert.equal(after, 0); signal = active; return new Promise(done => { resolve = done }) }, connect: () => { throw new Error('must not open') } })
  t.after(stop); stop(); assert.ok(signal?.aborted); resolve(page([event(1)], 0)); await flush(); assert.deepEqual(received, [])
  assert.ok(!validEvent({ ...event(1), runId: 'other' }, 'r')); assert.ok(!validEvent({ ...event(1), sequence: 0 }, 'r'))
  assert.ok(!validEvent({ ...event(1), eventId: 'other:1' }, 'r'))
})
