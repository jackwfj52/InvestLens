<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ApiError } from '../services/apiClient.ts'
import { assets, canonicalDecimal, compareDecimal, inputApi, percentToRatio, ratioToPercent, weightsSumToOne } from '../services/researchInputs.ts'
import type { AllocationMode, DecisionVersion, Draft, Position, Proposal, Snapshot } from '../services/researchInputs.ts'

const portfolio = ref<Snapshot>()
const decision = ref<DecisionVersion>()
const portfolioId = ref(''), decisionId = ref('')
const busy = ref(false), message = ref(''), errors = ref<{ field: string | null; message: string }[]>([])
const conflict = ref(false), traceId = ref<string | null>(null)
const dirtyPortfolio = ref(false), dirtyDecision = ref(false)
const form = reactive({ name: '我的研究组合', weights: ['20', '10', '0', '70'],
  inputText: '', target: assets[0].id as string, funding: 'CASH:USD', mode: 'TARGET_WEIGHT' as AllocationMode,
  allocation: '30', horizon: '', unit: 'MONTH' as 'DAY' | 'MONTH' | 'YEAR', maxWeight: '30', maxLoss: '8',
  hypothesis: '', mechanism: '', testMethod: '' })
watch(() => form.weights, () => { dirtyPortfolio.value = true }, { deep: true, flush: 'sync' })
watch(() => [form.inputText, form.target, form.funding, form.mode, form.allocation, form.horizon, form.unit,
  form.maxWeight, form.maxLoss, form.hypothesis, form.mechanism, form.testMethod], () => { dirtyDecision.value = true }, { flush: 'sync' })
const canConfirm = computed(() => decision.value && !dirtyDecision.value && !dirtyPortfolio.value && !busy.value
  && decision.value.inputStatus !== 'CONFIRMED' && decision.value.missingFields.length === 0
  && decision.value.portfolioSnapshotId === portfolio.value?.snapshotId)
function fail(field: string, text: string): never { throw new ApiError('VALIDATION_ERROR', 400, text, [{ field, code: 'INVALID_FIELD', message: text }]) }
function fieldLink(field: string | null): string {
  if (field?.includes('horizon')) return '#field-horizon'
  if (field?.includes('positions') || field === 'portfolioSnapshotId') return '#portfolio-title'
  if (field?.includes('hypothes')) return '#field-hypotheses'
  return '#decision-title'
}
function number(field: string, value: string, ratio = true) {
  try { return ratio ? percentToRatio(value) : canonicalDecimal(value) }
  catch (error) { return fail(field, error instanceof Error ? error.message : '数值格式不正确') }
}
function remember() {
  try { localStorage.setItem('investlens.input-ids', JSON.stringify({ portfolioId: portfolioId.value, decisionId: decisionId.value })) } catch { /* URL/manual IDs also work when storage is disabled. */ }
}
function newPortfolio() {
  portfolio.value = undefined; portfolioId.value = ''; form.name = '我的研究组合'; form.weights = ['20', '10', '0', '70']
  dirtyPortfolio.value = true; remember()
}
function newDecision() {
  decision.value = undefined; decisionId.value = ''; form.inputText = ''; form.horizon = ''
  form.hypothesis = ''; form.mechanism = ''; form.testMethod = ''; dirtyDecision.value = true; remember()
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; errors.value = []; message.value = ''; conflict.value = false; traceId.value = null
  try { await action() }
  catch (error) {
    if (error instanceof ApiError) {
      errors.value = error.errors.length ? [...error.errors] : [{ field: null, message: error.message }]
      conflict.value = error.code === 'VERSION_CONFLICT'; traceId.value = error.traceId
    } else errors.value = [{ field: null, message: '读取或保存失败，请重试' }]
  } finally { busy.value = false }
}
function hydratePortfolio(snapshot: Snapshot) {
  if (snapshot.dataMode !== 'SYNTHETIC' || snapshot.valuationBasis !== 'NORMALIZED' || snapshot.baseCurrency !== 'USD'
    || snapshot.totalValue !== '100' || snapshot.positions.some(p => !assets.some(a => a.id === p.instrumentId))) {
    fail('positions', '此表单仅打开已登记的合成、USD 归一化组合')
  }
  portfolio.value = snapshot; portfolioId.value = snapshot.portfolioId
  form.weights = assets.map(asset => ratioToPercent(snapshot.positions.find(p => p.instrumentId === asset.id)?.weight ?? '0'))
  dirtyPortfolio.value = false
}
function hydrateDecision(saved: DecisionVersion) {
  decision.value = saved; decisionId.value = saved.decisionId
  form.inputText = saved.inputText
  form.target = saved.action?.instrumentId ?? assets[0].id; form.funding = saved.action?.fundingInstrumentId ?? 'CASH:USD'
  form.mode = saved.action?.allocationMode ?? 'TARGET_WEIGHT'
  form.allocation = saved.action?.targetWeight ? ratioToPercent(saved.action.targetWeight) : saved.action?.deltaPercentagePoints ?? '30'
  form.horizon = saved.horizon?.value.toString() ?? ''; form.unit = saved.horizon?.unit ?? 'MONTH'
  form.maxWeight = saved.constraints ? ratioToPercent(saved.constraints.maxTargetWeight) : '30'
  form.maxLoss = saved.constraints ? ratioToPercent(saved.constraints.maxScenarioLoss) : '8'
  form.hypothesis = saved.hypotheses?.[0]?.statement ?? ''; form.mechanism = saved.hypotheses?.[0]?.mechanism ?? ''
  form.testMethod = saved.hypotheses?.[0]?.testMethod ?? ''; dirtyDecision.value = false
}
async function loadPortfolio() { await perform(async () => {
  hydratePortfolio((await inputApi.portfolio(portfolioId.value.trim())).data); remember(); message.value = '已重新读取组合。决策仍引用原来的快照，保存输入后才会更新引用。'
}) }
async function loadDecision() { await perform(async () => {
  const saved = (await inputApi.decision(decisionId.value.trim())).data
  if ((saved.hypotheses?.length ?? 0) > 1 || (saved.action && (!assets.some(a => a.id === saved.action?.instrumentId)
    || !assets.some(a => a.id === saved.action?.fundingInstrumentId)))) fail('decisionId', '此表单支持单个假设与已登记的合成资产，请使用兼容的输入版本')
  hydrateDecision(saved); remember(); message.value = '已重新读取决策。请核对下方引用的组合快照。'
}) }
async function savePortfolio() { await perform(async () => {
  if (portfolio.value && (portfolio.value.dataMode !== 'SYNTHETIC' || portfolio.value.valuationBasis !== 'NORMALIZED'
    || portfolio.value.positions.some(p => !assets.some(a => a.id === p.instrumentId)))) fail('positions', '此表单仅编辑已登记的合成、归一化组合')
  const positions: Position[] = assets.map((asset, i) => ({ instrumentId: asset.id, quoteCurrency: 'USD', weight: number(`positions[${i}].weight`, form.weights[i] ?? '') }))
    .filter(p => p.instrumentId === 'CASH:USD' || p.weight !== '0')
  if (!weightsSumToOne(positions)) fail('positions', '组合权重必须精确合计为 100%')
  const asOf = new Date().toISOString()
  const result = portfolio.value
    ? await inputApi.revisePortfolio(portfolio.value.portfolioId, { schemaVersion: '1.0.0', expectedVersion: portfolio.value.version, positions, asOf })
    : await inputApi.createPortfolio({ schemaVersion: '1.0.0', name: form.name, baseCurrency: 'USD', valuationBasis: 'NORMALIZED', totalValue: '100', dataMode: 'SYNTHETIC', positions, asOf })
  hydratePortfolio(result.data); dirtyDecision.value = true; remember(); message.value = '组合已保存。请保存决策以引用这个快照。'
}) }
function draft(): Draft {
  if (!form.inputText.trim()) fail('inputText', '请填写投资想法')
  if (!portfolio.value || dirtyPortfolio.value) fail('portfolioSnapshotId', '请先保存组合，再保存输入')
  if (form.target === form.funding) fail('action.fundingInstrumentId', '资金来源必须与目标资产不同')
  const allocation = number(form.mode === 'TARGET_WEIGHT' ? 'action.targetWeight' : 'action.deltaPercentagePoints', form.allocation, form.mode === 'TARGET_WEIGHT')
  if (compareDecimal(allocation, '0') <= 0 || compareDecimal(allocation, '100') > 0) fail('action', '配置数值必须大于零且在允许范围内')
  const proposal: Proposal = { baseCurrency: 'USD', portfolioSnapshotId: portfolio.value.snapshotId,
    action: { type: 'REALLOCATE', instrumentId: form.target, fundingInstrumentId: form.funding, allocationMode: form.mode,
      ...(form.mode === 'TARGET_WEIGHT' ? { targetWeight: allocation } : { deltaPercentagePoints: allocation }) },
    constraints: { maxTargetWeight: number('constraints.maxTargetWeight', form.maxWeight), maxScenarioLoss: number('constraints.maxScenarioLoss', form.maxLoss) } }
  const missing = []
  if (!form.horizon) missing.push({ field: 'horizon', code: 'REQUIRED', message: '请填写研究期限' })
  else {
    if (!/^[1-9]\d{0,8}$/.test(form.horizon) || Number(form.horizon) > 2147483647) fail('horizon.value', '期限必须为正整数')
    proposal.horizon = { value: Number(form.horizon), unit: form.unit }
  }
  const hypothesisParts = [form.hypothesis, form.mechanism, form.testMethod].map(v => v.trim())
  if (hypothesisParts.some(Boolean) && !hypothesisParts.every(Boolean)) fail('hypotheses', '请补齐假设、机制与检验方法；若暂不填写，请清空三个字段后保存待补充输入')
  if (!hypothesisParts.every(Boolean)) missing.push({ field: 'hypotheses', code: 'REQUIRED', message: '请补齐假设、机制与检验方法' })
  else proposal.hypotheses = [{ schemaVersion: '1.0.0', hypothesisId: decision.value?.hypotheses?.[0]?.hypothesisId ?? 'hyp_manual', statement: form.hypothesis, mechanism: form.mechanism, testMethod: form.testMethod, supportStatus: 'UNVERIFIED' }]
  return { schemaVersion: '1.0.0', draftId: `draft_${crypto.randomUUID()}`, inputText: form.inputText,
    inputStatus: missing.length ? 'NEEDS_INPUT' : 'READY', missingFields: missing, proposedDecision: proposal }
}
async function saveDecision(confirm = false) { await perform(async () => {
  if (confirm && (!decision.value || dirtyDecision.value || dirtyPortfolio.value || decision.value.missingFields.length
    || decision.value.portfolioSnapshotId !== portfolio.value?.snapshotId)) fail('inputStatus', '请先保存完整输入，再确认')
  const saved = (await inputApi.saveDecision(draft(), decision.value, confirm)).data
  hydrateDecision(saved); remember(); message.value = saved.inputStatus === 'CONFIRMED' ? '当前输入版本已确认；尚未开始研究。' : '输入已保存；修改后的版本需要重新确认。'
}) }
onMounted(async () => {
  try {
    const ids = JSON.parse(localStorage.getItem('investlens.input-ids') ?? '{}') as Record<string, unknown>
    if (typeof ids.portfolioId === 'string') portfolioId.value = ids.portfolioId
    if (typeof ids.decisionId === 'string') decisionId.value = ids.decisionId
  } catch { /* Manual ID fields remain available. */ }
  if (portfolioId.value) await loadPortfolio()
  if (decisionId.value) await loadDecision()
})
</script>

<template>
  <section class="card input-card" aria-labelledby="portfolio-title" :aria-busy="busy">
    <div class="section-title"><h2 id="portfolio-title">01 当前组合</h2><span class="outline-label">合成输入 · USD · 归一化 100</span></div>
    <p class="hint">以下为手动合成组合。黄金和白银使用 ETF 代理，单位为每份基金，非现货金属。</p>
    <form @submit.prevent="savePortfolio" novalidate>
      <label v-if="!portfolio">组合名称<input v-model="form.name" :disabled="busy" maxlength="200" /></label>
      <div class="form-grid weights"><label v-for="(asset, i) in assets" :key="asset.id">{{ asset.label }} · %<input v-model="form.weights[i]" inputmode="decimal" :disabled="busy" /></label></div>
      <p v-if="portfolio" class="saved-meta">组合 v{{ portfolio.version }} · {{ portfolio.portfolioId }}<br />快照 {{ portfolio.snapshotId }}<br /><span v-if="dirtyPortfolio" class="error-text">有未保存的组合修改</span></p>
      <div class="button-row"><button class="primary-button" :disabled="busy">{{ busy ? '处理中…' : portfolio ? '保存组合新版本' : '保存组合' }}</button><button v-if="portfolio" type="button" class="secondary-button" :disabled="busy" @click="newPortfolio">新建组合</button></div>
    </form>
    <details class="reopen"><summary>按 ID 重新打开组合</summary><div class="inline-fields"><input v-model="portfolioId" aria-label="组合 ID" placeholder="组合 ID" :disabled="busy" /><button class="secondary-button" :disabled="busy || !portfolioId.trim()" @click="loadPortfolio">读取组合</button></div></details>
  </section>

  <section class="card input-card" aria-labelledby="decision-title" :aria-busy="busy">
    <div class="section-title"><h2 id="decision-title">02 投资想法与约束</h2><span class="quiet">修改后需重新确认</span></div>
    <form @submit.prevent="saveDecision(false)" novalidate>
      <label>投资想法<textarea v-model="form.inputText" rows="3" :disabled="busy" placeholder="描述你想改变什么，以及原因" /></label>
      <div class="form-grid"><label>目标资产<select v-model="form.target" :disabled="busy"><option v-for="asset in assets.slice(0, 3)" :key="asset.id" :value="asset.id">{{ asset.label }}</option></select></label>
        <label>资金来源<select v-model="form.funding" :disabled="busy"><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.label }}</option></select></label>
        <label>配置方式<select v-model="form.mode" :disabled="busy"><option value="TARGET_WEIGHT">调整到目标权重（%）</option><option value="DELTA_PERCENTAGE_POINTS">增加权重（百分点）</option></select></label>
        <label>{{ form.mode === 'TARGET_WEIGHT' ? '目标权重 · %' : '增加 · 百分点' }}<input v-model="form.allocation" inputmode="decimal" :disabled="busy" /></label></div>
      <p class="hint">目标权重 10% 表示最终占组合 10%；增加 10 个百分点表示从 20% 增至 30%。</p>
      <div class="form-grid"><label id="field-horizon">研究期限<input v-model="form.horizon" inputmode="numeric" :disabled="busy" placeholder="待补充" /><span v-if="decision?.missingFields.some(f => f.field === 'horizon') && !form.horizon" class="error-text">请补充研究期限后再确认</span></label>
        <label>期限单位<select v-model="form.unit" :disabled="busy"><option value="DAY">天</option><option value="MONTH">月</option><option value="YEAR">年</option></select></label>
        <label>目标资产权重上限 · %<input v-model="form.maxWeight" inputmode="decimal" :disabled="busy" /></label>
        <label>情景损失上限 · %<input v-model="form.maxLoss" inputmode="decimal" :disabled="busy" /></label></div>
      <label id="field-hypotheses">用户假设<textarea v-model="form.hypothesis" rows="2" :disabled="busy" placeholder="尚未检验的假设" /></label>
      <div class="form-grid"><label>作用机制<textarea v-model="form.mechanism" rows="2" :disabled="busy" /></label><label>检验方法<textarea v-model="form.testMethod" rows="2" :disabled="busy" /></label></div>
      <p class="hint">假设保持“未验证”。输入确认不代表假设成立。</p>
      <p v-if="decision" class="saved-meta">决策 v{{ decision.version }} · {{ decision.decisionId }}<br />状态：{{ dirtyDecision ? '有未保存修改' : decision.inputStatus === 'CONFIRMED' ? '已确认' : decision.inputStatus === 'READY' ? '待确认' : '待补充' }}<br />引用快照：{{ decision.portfolioSnapshotId }}<br /><span v-if="decision.portfolioSnapshotId !== portfolio?.snapshotId" class="error-text">当前打开的组合与决策引用不同，保存输入将更新引用。</span></p>
      <ul v-if="decision?.missingFields.length" class="field-errors" aria-label="待补充字段"><li v-for="field in decision.missingFields" :key="field.field"><a :href="fieldLink(field.field)">{{ field.field }}：{{ field.message }} ↗</a></li></ul>
      <div class="button-row"><button class="primary-button" :disabled="busy">保存输入</button><button type="button" class="secondary-button" :disabled="!canConfirm" @click="saveDecision(true)">确认当前输入</button><button v-if="decision" type="button" class="secondary-button" :disabled="busy" @click="newDecision">新建输入</button></div>
    </form>
    <details class="reopen"><summary>按 ID 重新打开决策</summary><div class="inline-fields"><input v-model="decisionId" aria-label="决策 ID" placeholder="决策 ID" :disabled="busy" /><button class="secondary-button" :disabled="busy || !decisionId.trim()" @click="loadDecision">读取决策</button></div></details>
  </section>
  <section v-if="message || errors.length" class="card input-feedback" aria-live="polite">
    <p v-if="message" role="status">{{ message }}</p><div v-if="errors.length" role="alert"><p v-if="conflict">版本已被其他页面修改。请先复制未保存内容，再重新读取最新版本后修订。</p><ul class="field-errors"><li v-for="(error, i) in errors" :key="i"><a :href="fieldLink(error.field)">{{ error.field ? `${error.field}：` : '' }}{{ error.message }} ↗</a></li></ul><small v-if="traceId">追踪 ID：{{ traceId }}</small></div>
  </section>
  <p class="hint">研究运行接口尚未接入。保存和确认仅记录输入，不会生成研究结果。</p>
</template>
