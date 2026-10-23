<script setup lang="ts">
import { ref, watch, onScopeDispose, onMounted } from 'vue'
import { subscribeProgress } from '../services/progress.ts'
import type { RunEvent, ProgressMode } from '../services/progress.ts'
import type { InputContext } from '../services/riskResearch.ts'
import { loadIntent, runApi, runIntent, saveIntent, rememberRun, lastRun } from '../services/runs.ts'
import type { Run, RunIntent } from '../services/runs.ts'
import DataModeBadge from './DataModeBadge.vue'
const props = defineProps<{ context: InputContext; scenarioId?: string; openedRun?: Run }>()
const emit = defineEmits<{ run: [value: Run | undefined] }>()
const run = ref<Run>(), busy = ref(false), error = ref('')
const events = ref<RunEvent[]>([]), progressError = ref('')
const connection = ref<ProgressMode>('CONNECTING')
let closeProgress: (() => void) | undefined
watch(() => run.value?.runId, id => {
  closeProgress?.(); events.value = []; progressError.value = ''
  if (!id) return
  rememberRun(id)
  closeProgress = subscribeProgress(id, event => {
    if (run.value?.runId !== id || events.value.some(saved => saved.sequence === event.sequence)) return
    events.value = [...events.value, event].slice(-100)
    if (run.value && !['COMPLETED','PARTIAL','FAILED','CANCELLED'].includes(run.value.status)) run.value = { ...run.value, status: event.status, stage: event.stage }
  }, state => { connection.value = state; progressError.value = state === 'POLLING' ? '事件连接不可用，正在轮询补读并自动重连；后台任务不受影响。' : '' }, () => { void syncTerminal(id) })
})
const restore = new AbortController()
onMounted(async () => {
  const id = lastRun(); if (!id || run.value) return
  try { const result = await runApi.get(id, restore.signal); if (!run.value && !restore.signal.aborted) run.value = result.data }
  catch { if (!restore.signal.aborted) error.value = '原任务暂时无法读取，可从历史侧栏重新打开；未自动创建新任务。' }
})
async function syncTerminal(id: string) {
  try { const result = await runApi.get(id, restore.signal); if (run.value?.runId === id) run.value = result.data }
  catch { if (!restore.signal.aborted && run.value?.runId === id) error.value = '终态已补读，报告状态暂时无法读取，请重试读取任务状态。' }
}
onScopeDispose(() => { restore.abort(); closeProgress?.() })
watch(() => props.openedRun, value => { if (value) run.value = value })
watch(run, value => emit('run', value))
let intent: RunIntent | undefined = loadIntent()
async function create(rerun = false) {
  if (busy.value) return
  busy.value = true; error.value = ''
  const context = props.context
  try {
    intent = runIntent(context, rerun ? undefined : intent, undefined, props.scenarioId); saveIntent(intent)
    const result = await runApi.create(intent)
    if (props.context.decision?.decisionId === result.data.decisionId && props.context.decision.version === result.data.decisionVersion
      && props.context.portfolio?.snapshotId === result.data.portfolioSnapshotId) run.value = result.data
  } catch (e) { error.value = e instanceof Error ? e.message : '创建任务失败，可使用原幂等键重试' }
  finally { busy.value = false }
}
async function refresh() {
  if (busy.value || !run.value) return
  busy.value = true; error.value = ''; const id = run.value.runId
  try { const result = await runApi.get(id); if (run.value?.runId === id) run.value = result.data }
  catch (e) { error.value = e instanceof Error ? e.message : '读取任务失败' }
  finally { busy.value = false }
}
</script>
<template>
  <section class="card" aria-labelledby="run-title" :aria-busy="busy">
    <h2 id="run-title">后台研究任务</h2>
    <p>任务固定已确认的输入与已计算的显式冲击。模型默认关闭；禁用角色时仅发布明确标记的部分报告。</p>
    <p v-if="!scenarioId" class="hint">请先在情景区计算当前输入的显式冲击，再创建研究。</p>
    <button :disabled="!context.ready || !scenarioId || busy" @click="create()">{{ busy ? '处理中…' : '创建 / 恢复本次任务' }}</button>
    <button class="secondary-button" :disabled="!run || busy" @click="refresh">读取任务状态</button>
    <button class="secondary-button" :disabled="!context.ready || !scenarioId || busy || !run || !['COMPLETED','PARTIAL','FAILED','CANCELLED'].includes(run.status)" @click="create(true)">使用当前输入明确重新运行</button>
    <p v-if="run && (run.decisionId !== context.decision?.decisionId || run.decisionVersion !== context.decision?.version || run.portfolioSnapshotId !== context.portfolio?.snapshotId)" class="hint">正在查看历史固定版本，与当前编辑输入不同，不会覆盖当前输入。</p>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <p v-if="progressError" class="hint" role="status">{{ progressError }}</p>
    <p v-if="run" class="quiet" role="status">进度连接：{{ connection }}</p>
    <ol v-if="events.length" class="progress-events"><li v-for="event in events" :key="event.eventId">{{ event.createdAt }} · {{ event.stage ?? '队列' }} · {{ event.type }} · {{ event.status }}</li></ol>
    <div v-if="run" role="status" aria-live="polite">
      <DataModeBadge :mode="run.dataMode" />
      <p>任务 {{ run.runId }} · {{ run.dataMode }} · {{ run.status }} · {{ run.stage ?? '排队中' }}</p>
      <p>固定决策 v{{ run.decisionVersion }} / 组合 v{{ run.portfolioVersion }} · 行情尝试 {{ run.budget.dataAttempts }} / 8 · 模型尝试 {{ run.budget.modelAttempts }} / 5</p>
      <p v-if="run.errorCode">停止原因：{{ run.errorCode }}。{{ run.reportId ? '' : '当前没有已发布报告。' }}</p>
    </div>
  </section>
</template>
