<script setup lang="ts">
import { ref } from 'vue'

const backendStatus = ref('等待连接')

async function checkBackend() {
  try {
    const response = await fetch('/actuator/health')
    backendStatus.value = response.ok ? 'Gateway 在线' : 'Gateway 异常'
  } catch {
    backendStatus.value = '尚未启动 Gateway'
  }
}
</script>

<template>
  <main class="shell">
    <p class="eyebrow">CODECYCLE / FOUNDATION</p>
    <h1>工程知识，持续生长。</h1>
    <p class="intro">基于 Commit 增量分析、AI Review、Knowledge、RAG 与 MCP 的开发者知识迭代平台。</p>
    <section class="card">
      <div><span class="label">运行状态</span><strong>{{ backendStatus }}</strong></div>
      <button type="button" @click="checkBackend">检查 Gateway</button>
    </section>
  </main>
</template>

<style>
:root { color: #e8edf2; background: #0d1117; font-family: Inter, ui-sans-serif, system-ui, sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; min-width: 320px; }
.shell { min-height: 100vh; max-width: 960px; margin: 0 auto; padding: 12vh 8vw; background: radial-gradient(circle at 80% 10%, #1f3b47 0, transparent 38%); }
.eyebrow, .label { color: #7ed6c3; letter-spacing: .16em; font-size: .75rem; }
h1 { max-width: 640px; margin: 1rem 0; font-size: clamp(2.5rem, 8vw, 5.5rem); line-height: .98; }
.intro { max-width: 560px; color: #9caab5; font-size: 1.1rem; line-height: 1.7; }
.card { display: flex; align-items: center; justify-content: space-between; max-width: 560px; margin-top: 4rem; padding: 1.25rem; border: 1px solid #2b414b; border-radius: 16px; background: #121c23cc; }
.card div { display: grid; gap: .45rem; }
button { cursor: pointer; border: 0; border-radius: 999px; padding: .75rem 1.1rem; color: #0d1117; background: #7ed6c3; font-weight: 700; }
button:hover { background: #a4eadb; }
</style>
