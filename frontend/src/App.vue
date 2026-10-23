<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/workbench" aria-label="InvestLens 首页">
        <span class="brand-mark" aria-hidden="true">IL</span>
        <span>InvestLens<small>投资决策研究</small></span>
      </RouterLink>
      <nav aria-label="主导航">
        <RouterLink class="nav-link" to="/workbench"><span aria-hidden="true">▦</span>研究工作台</RouterLink>
      </nav>
      <p class="sidebar-note">先检验证据与风险，<br />再形成自己的判断。</p>
      <span class="local-label"><span aria-hidden="true">●</span> 本地工作空间</span>
    </aside>
    <div class="workspace">
      <header class="topbar"><span>研究中心</span><span class="quiet">投资决策支持</span></header>
      <main id="main-content" tabindex="-1"><RouterView /></main>
      <footer>研究用于辅助独立判断。市场表现与用户假设需要分别检验。</footer>
    </div>
  </div>
</template>
