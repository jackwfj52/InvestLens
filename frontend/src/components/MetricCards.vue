<script setup lang="ts">
import { displayNumber } from '../services/riskResearch.ts'
import type { Metric } from '../services/riskResearch.ts'
defineProps<{ metrics: Metric[] }>()
const names: Record<string, string> = { PERIOD_RETURN: '区间收益', ANNUALIZED_VOLATILITY: '年化波动率', MAX_DRAWDOWN: '最大回撤', WEIGHT_CHANGE: '目标权重变化', TARGET_WEIGHT_AFTER: '调仓后目标权重', SCENARIO_RETURN_BEFORE: '调仓前收益', SCENARIO_RETURN_AFTER: '调仓后收益', SCENARIO_PNL_BEFORE: '调仓前损益', SCENARIO_PNL_AFTER: '调仓后损益', SCENARIO_RETURN_CHANGE: '收益变化' }
</script>
<template>
  <div class="metric-grid"><article v-for="metric in metrics" :key="metric.metricId" class="metric-card">
    <h3>{{ names[metric.name] ?? metric.name }}</h3>
    <strong>{{ metric.status === 'CALCULABLE' ? displayNumber(metric.value, metric.unit) : '不可计算' }}</strong>
    <p v-if="metric.currency">{{ metric.currency }} · {{ metric.unit === 'BASE_CURRENCY_UNIT' ? '归一化示例金额' : '金额' }}</p>
    <p v-if="metric.reason" class="error-text">{{ metric.reason === 'INSUFFICIENT_SAMPLES' ? `样本不足：实际 ${metric.sampleCount}，至少需要 ${metric.parameters.minimumSamples}` : metric.reason }}</p>
    <p v-if="metric.sampleWindow">{{ metric.sampleWindow.start }} 至 {{ metric.sampleWindow.end }} · {{ metric.sampleCount }} 个{{ metric.name === 'ANNUALIZED_VOLATILITY' ? '收益' : '价格' }}样本</p>
    <details><summary>公式与引用</summary><p>{{ metric.formulaVersion }}<span v-if="metric.parameters.annualizationFactor"> · 年化因子 {{ metric.parameters.annualizationFactor }}</span></p><p class="hash-text">输入 {{ metric.inputHash }}</p><p v-for="id in metric.evidenceRefs" :key="id" class="hash-text">证据 {{ id }}</p></details>
  </article></div>
</template>
