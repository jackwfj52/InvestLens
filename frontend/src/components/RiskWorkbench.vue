<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { ApiError } from '../services/apiClient.ts'
import { useAsyncRequest } from '../composables/useAsyncRequest.ts'
import { riskApi, eligible, buildShocks, safeLink, sameScenarioContext } from '../services/riskResearch.ts'
import type { InputContext, CalculationSnapshot, Bundle } from '../services/riskResearch.ts'
import { assets } from '../services/researchInputs.ts'
import MetricCards from './MetricCards.vue'
import ScenarioComparison from './ScenarioComparison.vue'

const props = defineProps<{ context: InputContext }>()
const emit = defineEmits<{ scenario: [id: string | undefined] }>()
const { data: candidates, phase: sourcePhase, error: sourceError, execute: loadSources } = useAsyncRequest(async signal => (await riskApi.candidates(signal)).data)
const end = new Date(); end.setUTCDate(end.getUTCDate() - 1)
const start = new Date(end); start.setUTCDate(start.getUTCDate() - 110)
const dates = reactive({ start: start.toISOString().slice(0, 10), end: end.toISOString().slice(0, 10) })
const shocks = reactive<Record<string, string>>({})
const bundleId = ref(''), scenarioId = ref(''), reopenKind = ref<'scenario' | 'bundle'>('scenario')
let cachedSnapshot: CalculationSnapshot | undefined, debounce: ReturnType<typeof setTimeout> | undefined
let autoScenario = false
const activePortfolio = computed(() => props.context.portfolio)
const canHistory = computed(() => props.context.ready && !!activePortfolio.value?.positions.some(p => p.instrumentId !== 'CASH:USD')
  && activePortfolio.value.positions.filter(p => p.instrumentId !== 'CASH:USD').every(p => eligible(candidates.value?.find(c => c.instrument.instrumentId === p.instrumentId))))
const label = (id: string) => assets.find(a => a.id === id)?.label ?? candidates.value?.find(c => c.instrument.instrumentId === id)?.instrument.symbol ?? id
function error(code: string, message: string): never { throw new ApiError(code, 0, message) }
function remember() { try { localStorage.setItem('investlens.risk-ids', JSON.stringify({ bundleId: bundleId.value, scenarioId: scenarioId.value })) } catch { /* Manual IDs remain available. */ } }

const { data: calculation, phase: calculationPhase, error: calculationError, execute: calculate, reset: resetCalculation } = useAsyncRequest(async signal => {
  const p = activePortfolio.value
  if (!p || !canHistory.value) error('DATA_UNUSABLE', '当前组合没有已获准的历史行情输入')
  const windows = p.positions.filter(position => position.instrumentId !== 'CASH:USD').map(position => ({ instrumentId: position.instrumentId, ...dates }))
  const snapshot = (await riskApi.freeze(p.snapshotId, windows, signal)).data
  const evidence = await Promise.all(snapshot.marketInputs.map(async input => (await riskApi.evidence(input.evidenceRef, signal)).data))
  if (!snapshot.replayable) return { snapshot, evidence, bundle: undefined as Bundle | undefined }
  const bundle = (await riskApi.calculate(snapshot.snapshotId, signal)).data
  return { snapshot, evidence, bundle }
})
const { data: scenario, phase: scenarioPhase, error: scenarioError, execute: executeScenario, reset: resetScenario } = useAsyncRequest(async signal => {
  const p = activePortfolio.value, d = props.context.decision
  if (!p || !d || !props.context.ready) error('INPUT_NOT_CONFIRMED', '请先保存组合并确认当前决策')
  let values
  try { values = buildShocks(p, { ...shocks }) } catch (failure) { error('VALIDATION_ERROR', failure instanceof Error ? failure.message : '冲击格式不正确') }
  let snapshot = cachedSnapshot
  if (!snapshot || snapshot.portfolioRef.snapshotId !== p.snapshotId) {
    snapshot = (await riskApi.freeze(p.snapshotId, [], signal)).data
    if (signal.aborted) throw new DOMException('aborted', 'AbortError')
    cachedSnapshot = snapshot
  }
  return (await riskApi.scenario({ decisionId: d.decisionId, decisionVersion: d.version,
    calculationSnapshotId: snapshot.snapshotId, portfolioSnapshotId: p.snapshotId, shocks: values }, signal)).data
})
function runScenario() { clearTimeout(debounce); resetScenario(); autoScenario = true; void executeScenario() }
function runCalculation() { resetCalculation(); void calculate() }
watch(scenario, value => { emit('scenario', value && sameScenarioContext(value, props.context) ? value.scenarioId : undefined); if (value) { scenarioId.value = value.scenarioId; remember() } }, { flush: 'sync' })
watch(calculation, value => { if (value?.bundle) { bundleId.value = value.bundle.bundleId; remember() } })
watch(() => [props.context.portfolio?.snapshotId, props.context.decision?.decisionId, props.context.decision?.version, props.context.ready], () => {
  autoScenario = false; clearTimeout(debounce); cachedSnapshot = undefined; resetScenario(); resetCalculation()
  for (const key of Object.keys(shocks)) delete shocks[key]
  props.context.portfolio?.positions.forEach(p => { shocks[p.instrumentId] = '' })
}, { immediate: true, flush: 'sync' })
watch(shocks, () => {
  clearTimeout(debounce); resetScenario()
  if (autoScenario && props.context.ready) debounce = setTimeout(() => void executeScenario(), 300)
}, { flush: 'sync' })
watch(dates, resetCalculation, { flush: 'sync' })
function example() {
  if (activePortfolio.value?.dataMode !== 'SYNTHETIC') return
  const values: Record<string, string> = { 'ETF:SYNTHETIC:DEMO_GOLD:USD': '-20', 'ETF:SYNTHETIC:DEMO_SILVER:USD': '-25', 'CASH:USD': '0' }
  activePortfolio.value.positions.forEach(p => { if (values[p.instrumentId] !== undefined) shocks[p.instrumentId] = values[p.instrumentId]! })
}
const { data: reopened, phase: reopenPhase, error: reopenError, execute: reopen, reset: resetReopen } = useAsyncRequest(async signal => {
  const kind = reopenKind.value, id = kind === 'scenario' ? scenarioId.value.trim() : bundleId.value.trim()
  if (!id) error('VALIDATION_ERROR', '请填写要重新读取的结果 ID')
  if (kind === 'scenario') return { kind, scenario: (await riskApi.savedScenario(id, signal)).data, bundle: undefined }
  const bundle = (await riskApi.bundle(id, signal)).data
  const snapshot = (await riskApi.snapshot(bundle.calculationSnapshotId, signal)).data
  const evidence = await Promise.all(snapshot.marketInputs.map(async input => (await riskApi.evidence(input.evidenceRef, signal)).data))
  return { kind, bundle, snapshot, evidence, scenario: undefined }
})
watch(reopenKind, resetReopen, { flush: 'sync' })
function readHistory() { resetReopen(); remember(); void reopen() }
const historyMismatch = computed(() => reopened.value?.scenario
  ? !sameScenarioContext(reopened.value.scenario, props.context)
  : !!reopened.value?.snapshot && reopened.value.snapshot.portfolioRef.snapshotId !== props.context.portfolio?.snapshotId)
onMounted(() => {
  void loadSources()
  try { const ids = JSON.parse(localStorage.getItem('investlens.risk-ids') ?? '{}') as Record<string, unknown>
    if (typeof ids.bundleId === 'string') bundleId.value = ids.bundleId
    if (typeof ids.scenarioId === 'string') scenarioId.value = ids.scenarioId
  } catch { /* Keep manual reopen available. */ }
})
onScopeDispose(() => clearTimeout(debounce))
</script>

<template>
  <section class="card input-card" aria-labelledby="risk-input-title">
    <div class="section-title"><h2 id="risk-input-title">03 固定输入与来源</h2><span class="outline-label">{{ activePortfolio?.dataMode ?? '尚无组合' }}</span></div>
    <p v-if="activePortfolio" class="saved-meta">模式 {{ activePortfolio.dataMode }} · 组合 v{{ activePortfolio.version }} · {{ activePortfolio.asOf }}<br />快照 {{ activePortfolio.snapshotId }}<br />内容 {{ activePortfolio.contentHash }}</p>
    <p v-else class="hint">先保存组合，再保存并确认决策。</p>
    <p v-if="!context.ready" class="hint">当前输入尚未确认或存在未保存修改。完成保存和确认后可计算情景。</p>
    <p v-if="sourcePhase === 'loading'" role="status">正在读取数据源资格…</p>
    <p v-if="sourceError" class="error-text" role="alert">{{ sourceError.message }}</p>
    <div class="table-scroll" v-if="candidates"><table><caption>服务端行情来源资格（不代表已有可算价格）</caption><thead><tr><th>资产</th><th>来源</th><th>研究资格</th><th>存储许可</th></tr></thead><tbody><tr v-for="candidate in candidates" :key="candidate.instrument.instrumentId"><th>{{ candidate.instrument.symbol }}<span v-if="candidate.instrument.proxyFor"> · {{ candidate.instrument.proxyFor }} ETF 代理</span></th><td>{{ candidate.instrument.provider }}</td><td>{{ eligible(candidate) ? '已通过' : '待确认 / 禁算' }}</td><td>{{ candidate.permissions.storagePolicy }}</td></tr></tbody></table></div>
    <button class="secondary-button" :disabled="sourcePhase === 'loading'" @click="loadSources">刷新来源资格</button>
  </section>
  <section class="card input-card" aria-labelledby="history-risk-title" :aria-busy="calculationPhase === 'loading'">
    <h2 id="history-risk-title">04 历史指标</h2>
    <p v-if="!canHistory" class="hint">当前组合尚无获准的真实历史价格。合成组合不产生真实历史指标；行情使用条件、复权或来源资格未通过时保持禁算。</p>
    <div class="form-grid"><label>行情开始日期<input type="date" v-model="dates.start" /></label><label>行情结束日期<input type="date" v-model="dates.end" /></label></div>
    <button class="primary-button" :disabled="!canHistory || calculationPhase === 'loading'" @click="runCalculation">{{ calculationPhase === 'loading' ? '正在取得固定输入并计算…' : '计算历史指标' }}</button>
    <p v-if="calculationError" class="error-text" role="alert">计算失败：{{ calculationError.message }}<span v-if="calculationError.traceId"> · 追踪 {{ calculationError.traceId }}</span></p>
    <div v-if="calculation"><p class="saved-meta">{{ calculation.snapshot.snapshotId }} · 可重放 {{ calculation.snapshot.replayable ? '是' : '否' }}<br />输入 {{ calculation.snapshot.inputHash ?? '没有可计算输入哈希' }}</p><p v-for="gap in calculation.snapshot.gaps" :key="gap.code" class="error-text">{{ gap.code }}：{{ gap.message }}</p>
      <template v-if="calculation.bundle"><div v-for="asset in calculation.bundle.assets" :key="asset.instrumentId"><h3>{{ label(asset.instrumentId) }}</h3><MetricCards :metrics="calculation.bundle.metrics.filter(m => asset.metricRefs.includes(m.metricId))" /></div></template>
      <p v-for="fact in calculation.evidence" :key="fact.evidenceId" class="saved-meta">{{ fact.provider }} · {{ fact.quality }} · {{ fact.dataMode }} · {{ fact.storagePolicy }}<br />数据时间 {{ fact.asOf ?? '缺失' }} · 抓取时间 {{ fact.retrievedAt ?? '缺失' }}<br /><a v-if="safeLink(fact.sourceUrl)" :href="safeLink(fact.sourceUrl)" target="_blank" rel="noreferrer">来源 {{ fact.sourceUrl }}</a><br />证据 {{ fact.evidenceId }}</p>
    </div>
  </section>
  <section class="card input-card" aria-labelledby="scenario-title" :aria-busy="scenarioPhase === 'loading'">
    <div class="section-title"><h2 id="scenario-title">05 调仓与直接价格冲击</h2><span class="quiet">用户假设 · 确定性计算</span></div>
    <p class="hint">每个持仓都需填写 -100% 到 100% 的冲击，现金也不例外。结果使用原总值与已确认动作，不含交易成本。</p>
    <div class="form-grid"><label v-for="position in activePortfolio?.positions ?? []" :key="position.instrumentId">{{ label(position.instrumentId) }} · 冲击 %<input v-model="shocks[position.instrumentId]" inputmode="decimal" :disabled="!context.ready" placeholder="显式填写，例如 0" /></label></div>
    <div class="button-row"><button class="primary-button" :disabled="!context.ready" @click="runScenario">计算调仓与冲击</button><button v-if="activePortfolio?.dataMode === 'SYNTHETIC'" class="secondary-button" :disabled="!context.ready" @click="example">填入合成算术示例</button></div>
    <p class="hint">首次计算后，修改冲击会自动重算；期间仅显示当前参数的响应。历史记录仍可按 ID 读取。</p>
    <p v-if="scenarioPhase === 'loading'" role="status" aria-live="polite">正在计算当前冲击…</p>
    <p v-if="scenarioError" class="error-text" role="alert">{{ scenarioError.message }}<span v-if="scenarioError.traceId"> · 追踪 {{ scenarioError.traceId }}</span></p>
    <ScenarioComparison v-if="scenario" :result="scenario" />
    <p v-else-if="scenarioPhase === 'idle'" class="hint">尚无当前参数的计算结果。</p>
  </section>
  <section class="card input-card" aria-labelledby="reopen-risk-title">
    <h2 id="reopen-risk-title">06 重新读取已保存结果</h2><p class="hint">刷新后可按保留的 ID 读取原结果；读取不会生成新计算或研究 Run。</p>
    <label>结果类型<select v-model="reopenKind"><option value="scenario">情景结果</option><option value="bundle">历史指标</option></select></label>
    <label v-if="reopenKind === 'scenario'">情景 ID<input v-model="scenarioId" /></label><label v-else>指标 Bundle ID<input v-model="bundleId" /></label>
    <button class="secondary-button" @click="readHistory">读取原结果</button><p v-if="reopenPhase === 'loading'" role="status">读取中…</p><p v-if="reopenError" class="error-text" role="alert">{{ reopenError.message }}</p>
    <p v-if="historyMismatch" class="hint">以下为历史快照，和当前编辑的输入不同；显示的数值属于其原版本。</p>
    <ScenarioComparison v-if="reopened?.scenario" :result="reopened.scenario" />
    <template v-if="reopened?.bundle"><p class="saved-meta">历史指标 {{ reopened.bundle.bundleId }} · {{ reopened.bundle.dataMode }}<br />计算快照 {{ reopened.bundle.calculationSnapshotId }}<br />原组合 {{ reopened.snapshot?.portfolioRef.snapshotId }} · v{{ reopened.snapshot?.portfolioRef.version }}<br />{{ reopened.bundle.inputHash }}</p><div v-for="asset in reopened.bundle.assets" :key="asset.instrumentId"><h3>{{ label(asset.instrumentId) }}</h3><MetricCards :metrics="reopened.bundle.metrics.filter(m => asset.metricRefs.includes(m.metricId))" /></div><p v-for="fact in reopened.evidence" :key="fact.evidenceId" class="saved-meta">{{ fact.provider }} · {{ fact.quality }} · {{ fact.storagePolicy }}<br />数据时间 {{ fact.asOf ?? '缺失' }} · 抓取时间 {{ fact.retrievedAt ?? '缺失' }}<br />证据 {{ fact.evidenceId }}<br /><a v-if="safeLink(fact.sourceUrl)" :href="safeLink(fact.sourceUrl)" target="_blank" rel="noreferrer">来源 {{ fact.sourceUrl }}</a></p></template>
  </section>
</template>
