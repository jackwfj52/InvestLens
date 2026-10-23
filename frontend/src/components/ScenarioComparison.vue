<script setup lang="ts">
import MetricCards from './MetricCards.vue'
import { displayNumber } from '../services/riskResearch.ts'
import { assets } from '../services/researchInputs.ts'
import type { Scenario } from '../services/riskResearch.ts'
defineProps<{ result: Scenario }>()
const label = (id: string) => assets.find(a => a.id === id)?.label ?? id
</script>
<template>
  <p class="saved-meta">模式 {{ result.dataMode }} · {{ result.createdAt }}<br />情景 {{ result.scenarioId }}<br />组合快照 {{ result.portfolioRef.snapshotId }} · v{{ result.portfolioRef.version }}<br />决策 {{ result.decisionRef.decisionId }} · v{{ result.decisionRef.version }}<br />输入 {{ result.inputHash }}</p>
  <MetricCards :metrics="result.metrics" />
  <div class="table-scroll"><table><caption>固定快照下的调仓与损益</caption><thead><tr><th>资产</th><th>原权重</th><th>新权重</th><th>用户冲击</th><th>原损益</th><th>新损益</th></tr></thead><tbody><tr v-for="impact in result.impacts" :key="impact.instrumentId"><th>{{ label(impact.instrumentId) }}</th><td>{{ displayNumber(result.beforeWeights.find(w => w.instrumentId === impact.instrumentId)?.weight ?? null, 'RATIO') }}</td><td>{{ displayNumber(result.afterWeights.find(w => w.instrumentId === impact.instrumentId)?.weight ?? null, 'RATIO') }}</td><td>{{ displayNumber(result.shocks.find(s => s.instrumentId === impact.instrumentId)?.shock ?? null, 'RATIO') }}</td><td>{{ displayNumber(impact.pnlBefore, result.pnlUnit) }}</td><td>{{ displayNumber(impact.pnlAfter, result.pnlUnit) }}</td></tr></tbody></table></div>
  <p class="hint">金额单位：{{ result.currency }} · {{ result.pnlUnit === 'BASE_CURRENCY_UNIT' ? '归一化示例总值 100' : '绝对金额' }}。集中度 HHI（{{ result.concentration.includesCash ? '含现金' : '不含现金' }}）：{{ result.concentration.hhiBefore }} → {{ result.concentration.hhiAfter }} · {{ result.concentration.formulaVersion }}。</p>
  <ul class="rule-list"><li v-for="rule in result.ruleResults" :key="rule.ruleId"><span :class="rule.status === 'FAIL' ? 'error-text' : ''">{{ rule.ruleId === 'MAX_TARGET_WEIGHT' ? '目标权重上限' : '情景损失上限' }}：{{ rule.status === 'PASS' ? '通过' : rule.status === 'FAIL' ? '超限' : '未评估' }}</span> · 阈值 {{ displayNumber(rule.threshold, rule.unit) }}</li></ul>
  <ul class="hint"><li v-for="assumption in result.assumptions" :key="assumption">{{ assumption }}</li></ul>
</template>
