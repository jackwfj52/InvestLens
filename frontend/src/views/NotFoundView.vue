<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>
<template>
  <section class="card empty-state">
    <p class="eyebrow">404</p><h1>没有找到这个页面</h1>
    <p>页面地址可能已变化，可以返回研究工作台。</p>
    <RouterLink class="primary-link" to="/workbench">返回工作台</RouterLink>
  </section>
</template>
