<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { TaskResult, ToolCall } from './types'
import Icon from './Icon.vue'

const props = defineProps<{ result: TaskResult; toolCalls: ToolCall[] }>()
const selectedId = ref<number | null>(null)
const citationPanel = ref<HTMLElement | null>(null)
const report = computed(() => props.result.report)
const sources = computed(() => new Map(report.value?.citations.map(c => [c.id, c]) ?? []))
const selected = computed(() => selectedId.value == null ? null : sources.value.get(selectedId.value))
const selectedCall = computed(() => selected.value?.kind === 'TOOL' && selected.value.toolCallIndex != null ? props.toolCalls[selected.value.toolCallIndex] : undefined)
async function openCitation(id: number) {
  if (!sources.value.has(id)) return
  selectedId.value = id
  await nextTick()
  citationPanel.value?.focus({ preventScroll: true })
  citationPanel.value?.scrollIntoView?.({ block: 'nearest' })
}
</script>

<template>
  <div class="result natural-report">
    <div class="report-heading"><span class="eyebrow">任务结果</span><span class="report-source-note"><Icon name="book" :size="13" /> 根据本任务已有回执整理</span></div>
    <h3>{{ report?.summary ?? result.summary }}</h3>
    <template v-if="report">
      <section v-for="section in report.sections" :key="section.key" class="report-section">
        <h4>{{ section.title }}</h4>
        <p v-for="(statement, index) in section.statements" :key="index" class="report-statement">
          {{ statement.text }}<sup v-for="id in statement.citationIds" :key="id"><button v-if="sources.has(id)" class="citation-link" :title="sources.get(id)?.title" :aria-label="`查看引用 ${id}：${sources.get(id)?.title}`" @click="openCitation(id)">[{{ id }}]</button></sup>
        </p>
      </section>
      <div v-if="report.citations.length" class="report-sources"><span>引用来源</span><button v-for="source in report.citations" :key="source.id" :class="{ selected: source.id === selectedId }" @click="openCitation(source.id)"><b>[{{ source.id }}]</b>{{ source.title }}<small>{{ source.kind === 'KNOWLEDGE' ? '知识依据' : '数据回执' }}</small></button></div>
      <section v-if="selected" ref="citationPanel" class="citation-panel" tabindex="-1" :aria-label="`引用 ${selected.id} 原始来源`">
        <header><div><span class="tag soft">{{ selected.kind === 'KNOWLEDGE' ? '知识依据' : '数据回执' }} · [{{ selected.id }}]</span><h4>{{ selected.title }}</h4></div><button class="text-button" aria-label="关闭引用详情" @click="selectedId = null">关闭</button></header>
        <template v-if="selected.kind === 'KNOWLEDGE'">
          <dl class="citation-meta"><div><dt>文档</dt><dd>{{ selected.documentId }}</dd></div><div><dt>片段</dt><dd>{{ selected.chunkId }}</dd></div><div v-if="selected.score != null"><dt>检索相关度</dt><dd>{{ selected.score.toFixed(4) }} <small>不是结论置信度</small></dd></div></dl>
          <div class="citation-original-label">本任务保存的原文片段</div><blockquote>{{ selected.content }}</blockquote>
        </template>
        <template v-else-if="selectedCall"><dl class="citation-meta"><div><dt>工具</dt><dd>{{ selectedCall.toolName }}</dd></div><div><dt>执行时间</dt><dd>{{ new Date(selectedCall.startedAt).toLocaleString('zh-CN', { hour12: false }) }}</dd></div></dl><details open><summary>查询条件和原始回执</summary><pre>{{ JSON.stringify({ args: selectedCall.args, result: selectedCall.result }, null, 2) }}</pre></details></template>
        <p v-else class="notice">对应工具回执暂不可用，请在任务调用记录中核对。</p>
      </section>
      <div v-if="report.notes.length" class="report-notes"><p v-for="note in report.notes" :key="note"><Icon name="info" :size="13" />{{ note }}</p></div>
    </template>
    <template v-else>
      <p v-if="result.reportUnavailable" class="notice">自然语言汇报暂时不可用，任务记录未丢失，可稍后从历史记录重新打开。</p>
      <details v-if="result.findings.length" class="legacy-result"><summary>查看历史原始结果</summary><ul><li v-for="(finding, index) in result.findings" :key="index">{{ finding }}</li></ul></details>
    </template>
    <div v-if="!report && result.verification.length" class="verified"><Icon name="check" :size="16" />{{ result.verification.join(' · ') }}</div>
    <p v-for="warning in result.warnings" :key="warning" class="notice">{{ warning }}</p>
  </div>
</template>
