<script setup lang="ts">
import { onMounted } from 'vue'
import { apiClient } from '../services/apiClient.ts'
import { useAsyncRequest } from '../composables/useAsyncRequest.ts'
const { data, phase, error, execute } = useAsyncRequest(async signal => (await apiClient.get<{ cache: string; callBudget: string; message: string }>('/system/resilience', { signal })).data)
onMounted(execute)
</script>
<template><section class="card connection" aria-labelledby="resilience-title"><div><h2 id="resilience-title">缓存与降级状态</h2><p v-if="data" :class="data.cache === 'UNAVAILABLE' ? 'hint' : ''">{{ data.message }} · 调用预算：{{ data.callBudget }}</p><p v-else-if="error" class="error-text" role="alert">{{ error.message }}</p><p v-else>正在读取降级状态…</p></div><button class="secondary-button" :disabled="phase === 'loading'" @click="execute">检查缓存</button></section></template>
