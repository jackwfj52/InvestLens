<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { reportApi } from '../services/reports.ts'
import type { DecisionSummary } from '../services/reports.ts'
import type { Run } from '../services/runs.ts'
const emit = defineEmits<{ open: [run: Run] }>()
const items = ref<DecisionSummary[]>([]), runs = ref<Run[]>([]), cursor = ref<string | null>(null), runCursor = ref<string | null>(null), selected = ref(''), loading = ref(false), error = ref('')
let controller: AbortController | undefined, generation = 0
async function load(more = false) {
  controller?.abort(); const signal = (controller = new AbortController()).signal, current = ++generation
  loading.value = true; error.value = ''
  try { const result = await reportApi.decisions(more ? cursor.value ?? undefined : undefined, signal); if (current === generation) { items.value = more ? [...items.value, ...result.data.items] : result.data.items; cursor.value = result.data.nextCursor } }
  catch (failure) { if (!signal.aborted) error.value = failure instanceof Error ? failure.message : '历史读取失败' }
  finally { if (current === generation) loading.value = false }
}
async function select(id: string, more = false) {
  controller?.abort(); const signal = (controller = new AbortController()).signal, current = ++generation
  selected.value = id; if (!more) runs.value = []; loading.value = true; error.value = ''
  try { const result = await reportApi.runs(id, more ? runCursor.value ?? undefined : undefined, signal); if (current === generation) { runs.value = more ? [...runs.value, ...result.data.items] : result.data.items; runCursor.value = result.data.nextCursor } }
  catch (failure) { if (!signal.aborted) error.value = failure instanceof Error ? failure.message : '任务历史读取失败' }
  finally { if (current === generation) loading.value = false }
}
onMounted(() => void load()); onScopeDispose(() => { ++generation; controller?.abort() })
</script>
<template>
  <aside class="card history-panel" aria-labelledby="history-title" :aria-busy="loading"><div class="section-title"><h2 id="history-title">研究历史</h2><button class="secondary-button" :disabled="loading" @click="load()">刷新</button></div><p class="hint">只读打开原任务与原报告，不创建研究。</p><p v-if="error" class="error-text" role="alert">{{ error }}</p><p v-if="!loading && !items.length">尚无保存的决策。</p>
    <ul class="history-list"><li v-for="item in items" :key="item.decisionId"><button class="secondary-button" :aria-pressed="selected === item.decisionId" :disabled="loading" @click="select(item.decisionId)">{{ item.inputText }} · v{{ item.version }}</button><small>{{ item.updatedAt }} · {{ item.inputStatus }}</small></li></ul>
    <button v-if="cursor" :disabled="loading" @click="load(true)">更多决策</button>
    <h3 v-if="selected">全部运行版本</h3><p v-if="selected && !runs.length && !loading">该决策尚未运行。</p><ul class="history-list"><li v-for="run in runs" :key="run.runId"><button class="secondary-button" @click="emit('open', run)">{{ run.dataMode }} · {{ run.status }} · 决策 v{{ run.decisionVersion }} / 组合 v{{ run.portfolioVersion }}</button><small class="hash-text">{{ run.runId }}</small></li></ul><button v-if="runCursor && selected" :disabled="loading" @click="select(selected, true)">更多运行</button>
  </aside>
</template>
