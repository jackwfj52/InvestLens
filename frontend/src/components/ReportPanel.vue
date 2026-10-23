<script setup lang="ts">
import { computed, ref, watch, onScopeDispose } from 'vue'
import { reportApi, referenceId, reportMatches } from '../services/reports.ts'
import type { Report, EvidenceBoard, Figure } from '../services/reports.ts'
import type { Run } from '../services/runs.ts'
import { safeLink, displayNumber } from '../services/riskResearch.ts'
const props = defineProps<{ run?: Run }>()
const report = ref<Report>(), board = ref<EvidenceBoard>(), figures = ref<Figure[]>([]), error = ref(''), loading = ref(false)
let controller: AbortController | undefined, generation = 0
const names: Record<string, string> = { PERIOD_RETURN: '区间收益', ANNUALIZED_VOLATILITY: '年化波动率', MAX_DRAWDOWN: '最大回撤', WEIGHT_CHANGE: '目标权重变化', TARGET_WEIGHT_AFTER: '调仓后目标权重', SCENARIO_RETURN_BEFORE: '调仓前收益', SCENARIO_RETURN_AFTER: '调仓后收益', SCENARIO_PNL_BEFORE: '调仓前损益', SCENARIO_PNL_AFTER: '调仓后损益', SCENARIO_RETURN_CHANGE: '收益变化' }
const figureMap = computed(() => new Map(figures.value.map(figure => [figure.metricId, figure])))
const anchor = (id: string) => referenceId(props.run?.runId ?? '', id)
async function load() {
  controller?.abort(); const current = ++generation, run = props.run
  report.value = undefined; board.value = undefined; figures.value = []; error.value = ''; loading.value = false
  if (!run?.reportId) return
  const signal = (controller = new AbortController()).signal; loading.value = true
  try {
    const [saved, evidence, rendered] = await Promise.all([reportApi.report(run.runId, signal), reportApi.evidence(run.runId, signal), reportApi.figures(run.runId, signal)])
    if (current !== generation || signal.aborted) return
    if (saved.code === 'RUN_IN_PROGRESS' || !reportMatches(saved.data as Report, run)) throw new Error('报告与固定任务不匹配')
    report.value = saved.data as Report; board.value = evidence.data; figures.value = rendered.data
  } catch (failure) { if (!signal.aborted && current === generation) error.value = failure instanceof Error ? failure.message : '报告读取失败' }
  finally { if (current === generation) loading.value = false }
}
watch(() => [props.run?.runId, props.run?.reportId], load, { immediate: true })
onScopeDispose(() => { ++generation; controller?.abort() })
</script>
<template>
  <section class="card report-panel" aria-labelledby="report-title" :aria-busy="loading">
    <div class="section-title"><h2 id="report-title">已保存研究报告</h2><button class="secondary-button" :disabled="!run?.reportId || loading" @click="load">重新读取</button></div>
    <p v-if="loading" role="status">正在读取原报告和证据…</p>
    <p v-else-if="error" class="error-text" role="alert">{{ error }}</p>
    <p v-else-if="!report">{{ run && ['FAILED','CANCELLED'].includes(run.status) ? '任务已停止，没有已发布报告。研究检查点仍保留。' : '选择历史报告，或等待后台任务完成。读取不会重新运行研究。' }}</p>
    <template v-if="report">
      <p class="outline-label">{{ report.status }} · {{ report.dataMode }} · 数据质量 {{ report.dataQuality }} · {{ report.generationMode }}</p>
      <p>决策 v{{ report.decisionRef.version }} / 组合 v{{ report.portfolioRef.version }} · 发布 {{ report.publishedAt }}</p>
      <p class="hash-text">{{ report.reportId }}<br />冻结 {{ report.researchSnapshotId }}<br />{{ report.snapshotHash }}</p>
      <h3>条件化判断：{{ report.verdict.verdict }}</h3><p>{{ report.verdict.thesisAssessment }}</p>
      <p>必需范围：{{ report.scope.requiredAnalyses.join(' / ') }}；可选范围：{{ report.scope.optionalAnalyses.join(' / ') }}</p>
      <p>角色：<span v-for="(status, role) in report.roleResults" :key="role">{{ role }} = {{ status }}　</span></p>
      <h3>确定性指标</h3>
      <div class="metric-grid"><article v-for="metric in report.metricBundle.metrics" :id="anchor(metric.metricId)" :key="metric.metricId" class="metric-card" tabindex="-1">
        <h4>{{ names[metric.name] ?? metric.name }}</h4><strong>{{ figureMap.get(metric.metricId)?.displayValue ?? '不可计算' }}</strong>
        <p v-if="metric.reason">{{ metric.reason }}</p><details><summary>公式与来源</summary><p>{{ metric.formulaVersion }} · {{ metric.unit }} · {{ metric.currency ?? '无币种' }}</p>
          <p v-if="metric.sampleWindow">{{ metric.sampleWindow.start }} 至 {{ metric.sampleWindow.end }} · 样本 {{ metric.sampleCount }}</p><p class="hash-text">{{ metric.inputHash }}</p>
          <a v-for="id in metric.evidenceRefs" :key="id" :href="`#${anchor(id)}`">{{ id }} </a></details>
      </article></div>
      <h3>调仓权重与用户阈值</h3>
      <div class="table-scroll"><table><thead><tr><th>资产</th><th>调仓前</th><th>调仓后</th></tr></thead><tbody><tr v-for="before in report.scenarioResult.beforeWeights" :key="before.instrumentId"><td>{{ before.instrumentId }}</td><td>{{ displayNumber(before.weight, 'RATIO') }}</td><td>{{ displayNumber(report.scenarioResult.afterWeights.find(item => item.instrumentId === before.instrumentId)?.weight ?? null, 'RATIO') }}</td></tr></tbody></table></div>
      <p v-for="rule in report.scenarioResult.ruleResults" :key="rule.ruleId">{{ rule.ruleId }}：{{ rule.status }} · 阈值 {{ displayNumber(rule.threshold, rule.unit) }} <a v-for="id in rule.metricRefs" :key="id" :href="`#${anchor(id)}`">指标来源 </a>{{ rule.reason }}</p>
      <h3>假设与角色观点</h3><p v-for="item in report.hypothesisAssessments" :key="item.hypothesisId">{{ item.hypothesisId }} · {{ item.supportStatus }}：{{ item.reason }}</p>
      <article v-for="claim in report.claims" :id="anchor(claim.claimId)" :key="claim.claimId"><h4>{{ claim.role }} · {{ claim.relation }}</h4><p>{{ claim.text }}</p><p v-for="condition in [...claim.conditions, ...claim.unknowns, ...claim.invalidationConditions]" :key="condition">{{ condition }}</p><a v-for="id in [...claim.evidenceRefs, ...claim.metricRefs]" :key="id" :href="`#${anchor(id)}`">{{ id }} </a></article>
      <h3>未知项、失效条件与下一步</h3><ul><li v-for="item in [...report.verdict.unknowns, ...report.verdict.invalidationConditions, ...report.verdict.nextChecks]" :key="item">{{ item }}</li></ul>
      <h3>缺口与限制</h3><ul><li v-for="(gap, index) in report.gaps" :key="index">{{ gap.code }}：{{ gap.message }}</li><li v-for="item in report.limitations" :key="item">{{ item }}</li></ul>
      <h3>Evidence Board · 证据台账</h3>
      <article v-for="item in board?.items" :id="anchor(item.evidenceId)" :key="item.evidenceId" class="evidence-entry" tabindex="-1"><h4>{{ item.displayId }} · {{ item.type }} · {{ item.quality }}</h4><p>{{ item.provider }} · {{ item.dataMode }} · {{ item.asOf }} · 取回 {{ item.retrievedAt }}</p><p>保存策略 {{ item.storagePolicy }} · 模型许可 {{ item.modelInputAllowed ? '允许' : '禁止' }}</p><p class="hash-text">{{ item.evidenceId }}<br />{{ item.payloadHash ?? '不保存原文' }}</p><a v-if="safeLink(item.sourceUrl)" :href="safeLink(item.sourceUrl)" target="_blank" rel="noopener noreferrer">查看来源</a></article>
      <p v-for="(gap, index) in board?.gaps" :key="index">{{ gap.code }}：{{ gap.message }}</p>
      <details><summary>冻结版本</summary><p>引擎 {{ report.versions.engineVersion }} · 质量策略 {{ report.versions.qualityPolicyVersion }} · 模型配置 {{ report.versions.modelConfigVersion }}</p><p v-for="(version, name) in report.versions.promptVersions" :key="name">{{ name }}：{{ version }}</p><p>校验 {{ report.validatedAt }}</p></details>
    </template>
  </section>
</template>
