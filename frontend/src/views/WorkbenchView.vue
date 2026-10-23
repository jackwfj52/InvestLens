<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAsyncRequest } from '../composables/useAsyncRequest.ts'
import { checkBackend } from '../services/backendStatus.ts'
import ResearchInputs from '../components/ResearchInputs.vue'
import RiskWorkbench from '../components/RiskWorkbench.vue'
import RunControls from '../components/RunControls.vue'
import ReportPanel from '../components/ReportPanel.vue'
import ReportHistory from '../components/ReportHistory.vue'
import type { Run } from '../services/runs.ts'
import type { InputContext } from '../services/riskResearch.ts'
const context = ref<InputContext>({ ready: false })
const scenarioId = ref<string>(), activeRun = ref<Run>(), openedRun = ref<Run>()
function openRun(run: Run) { openedRun.value = run; activeRun.value = run }

const { phase, error, execute } = useAsyncRequest(checkBackend)
onMounted(execute)
</script>

<template>
  <div class="page-heading">
    <div><p class="eyebrow">RESEARCH WORKBENCH</p><h1>研究工作台</h1><p class="lead">把投资想法变成可检验的问题。</p></div>
    <span class="outline-label">证据 · 风险 · 独立判断</span>
  </div>

  <section class="connection card" aria-labelledby="connection-title" :aria-busy="phase === 'loading'">
    <div>
      <h2 id="connection-title"><span class="status-dot" :class="phase" aria-hidden="true"></span>本地服务连接</h2>
      <p v-if="phase === 'loading'" role="status" aria-live="polite">正在检查服务状态…</p>
      <p v-else-if="phase === 'success'" role="status" aria-live="polite">服务已就绪，可以保存与读取研究输入。</p>
      <p v-else-if="phase === 'error'" class="error-text" role="alert">{{ error?.message }}</p>
      <p v-else role="status">尚未检查连接。</p>
    </div>
    <button class="secondary-button" :disabled="phase === 'loading'" @click="execute">
      {{ phase === 'loading' ? '检查中…' : phase === 'error' ? '重新连接' : '检查连接' }}
    </button>
  </section>

  <section class="card introduction" aria-labelledby="start-title">
    <div class="section-title"><h2 id="start-title">从一个清晰的研究问题开始</h2><span class="quiet">研究流程</span></div>
    <ol class="research-steps">
      <li><span class="step-number">01</span><h3>整理输入</h3><p>明确当前组合、投资想法、研究期限与风险约束。</p></li>
      <li><span class="step-number">02</span><h3>检验证据与风险</h3><p>区分用户假设、来源事实和确定性计算结果。</p></li>
      <li><span class="step-number">03</span><h3>审视不同论点</h3><p>结合支持、反对与缺失证据，形成有条件的判断。</p></li>
    </ol>
  </section>

  <ResearchInputs @context="context = $event" />
  <RiskWorkbench :context="context" @scenario="scenarioId = $event" />
  <RunControls :context="context" :scenario-id="scenarioId" :opened-run="openedRun" @run="activeRun = $event" />
  <div class="report-layout"><ReportHistory @open="openRun" /><ReportPanel :run="activeRun" /></div>
</template>
