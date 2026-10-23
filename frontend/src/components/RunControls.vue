<script setup lang="ts">
import { ref, watch } from 'vue'
import type { InputContext } from '../services/riskResearch.ts'
import { loadIntent, runApi, runIntent, saveIntent } from '../services/runs.ts'
import type { Run, RunIntent } from '../services/runs.ts'
const props = defineProps<{ context: InputContext }>()
const run = ref<Run>(), busy = ref(false), error = ref('')
let intent: RunIntent | undefined = loadIntent()
watch(() => [props.context.decision?.decisionId, props.context.decision?.version, props.context.portfolio?.snapshotId], () => { run.value = undefined })
async function create(rerun = false) {
  if (busy.value) return
  busy.value = true; error.value = ''
  const context = props.context
  try {
    intent = runIntent(context, rerun ? undefined : intent); saveIntent(intent)
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
    <p>任务固定已确认的输入版本。刷新页面或重试创建会复用原任务；后续处理器仍在建设中，当前任务可能因配置缺失而失败。</p>
    <button :disabled="!context.ready || busy" @click="create()">{{ busy ? '处理中…' : '创建 / 恢复本次任务' }}</button>
    <button class="secondary-button" :disabled="!run || busy" @click="refresh">读取任务状态</button>
    <button class="secondary-button" :disabled="!context.ready || busy || !run || !['COMPLETED','PARTIAL','FAILED','CANCELLED'].includes(run.status)" @click="create(true)">明确重新运行</button>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <div v-if="run" role="status" aria-live="polite">
      <p>任务 {{ run.runId }} · {{ run.dataMode }} · {{ run.status }} · {{ run.stage ?? '排队中' }}</p>
      <p>固定决策 v{{ run.decisionVersion }} / 组合 v{{ run.portfolioVersion }} · 行情尝试 {{ run.budget.dataAttempts }} / 8 · 模型尝试 {{ run.budget.modelAttempts }} / 5</p>
      <p v-if="run.errorCode">停止原因：{{ run.errorCode }}。{{ run.reportId ? '' : '当前没有已发布报告。' }}</p>
    </div>
  </section>
</template>
