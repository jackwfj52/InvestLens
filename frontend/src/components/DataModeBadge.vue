<script setup lang="ts">
import { computed } from 'vue'
import { describeMode } from '../services/dataModes.ts'
const props = defineProps<{ mode?: string }>()
const description = computed(() => describeMode(props.mode))
</script>
<template><span class="outline-label" :title="description.hint">{{ description.label }}</span><p class="hint">{{ description.hint }}</p></template>
