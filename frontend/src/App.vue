<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from './api'
import type { AgentTask, TaskEvent } from './types'

const examples = [
  '分析最近一周3号采区高频违规事件，并根据安全规程给出处置建议',
  '分析最近24小时瓦斯相关告警，并生成巡查计划',
  '启动 camera-03 的 intrusion_detection 检测任务',
]
const tab = ref<'console' | 'history' | 'eval'>('console')
const query = ref(examples[0])
const current = ref<AgentTask | null>(null)
const history = ref<AgentTask[]>([])
const timeline = ref<TaskEvent[]>([])
const evaluation = ref<Record<string, any> | null>(null)
const busy = ref(false)
const error = ref('')
let stream: EventSource | null = null

const isWaiting = computed(() => current.value?.state === 'WAITING_APPROVAL')
const riskClass = computed(() => `risk-${current.value?.plan?.riskLevel?.toLowerCase() ?? 'low'}`)
const eventTypes = ['TASK_STATE_CHANGED', 'PLAN_CREATED', 'TOOL_STARTED', 'TOOL_FINISHED', 'RAG_RETRIEVED',
  'WAITING_APPROVAL', 'APPROVED', 'REJECTED', 'VERIFICATION', 'FINAL_RESULT', 'ERROR']

async function createTask() {
  if (!query.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  timeline.value = []
  stream?.close()
  try {
    current.value = await api.createTask(query.value)
    connect(current.value.taskId)
    await refreshTask()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function connect(id: string) {
  stream = new EventSource(`/api/agent/tasks/${id}/stream`)
  for (const type of eventTypes) {
    stream.addEventListener(type, async (raw) => {
      const event = JSON.parse((raw as MessageEvent).data) as TaskEvent
      if (!timeline.value.some(item => item.sequence === event.sequence)) timeline.value.push(event)
      await refreshTask()
      if (type === 'FINAL_RESULT' || type === 'ERROR') {
        stream?.close()
        await loadHistory()
      }
    })
  }
  stream.onerror = () => {
    if (current.value && !['COMPLETED', 'FAILED'].includes(current.value.state)) error.value = 'SSE 连接已中断，可从任务历史重新打开。'
  }
}

async function refreshTask() {
  if (current.value) current.value = await api.task(current.value.taskId)
}

async function decide(decision: 'approve' | 'reject') {
  if (!current.value) return
  busy.value = true
  try {
    current.value = await api.decide(current.value.taskId, decision,
      decision === 'approve' ? '经演示操作员确认执行' : '演示操作员拒绝系统变更')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally { busy.value = false }
}

async function loadHistory() {
  history.value = await api.tasks().catch(() => [])
}

async function openTask(task: AgentTask) {
  current.value = await api.task(task.taskId)
  tab.value = 'console'
  timeline.value = []
  if (!['COMPLETED', 'FAILED'].includes(current.value.state)) connect(task.taskId)
}

async function loadEval() {
  evaluation.value = await api.eval().catch((e) => ({ status: 'ERROR', message: String(e) }))
}

function percent(value?: number) { return value === undefined ? '—' : `${(value * 100).toFixed(2)}%` }
function formatTime(value: string) { return new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) }
function compact(value: unknown) {
  const text = JSON.stringify(value)
  return text.length > 180 ? `${text.slice(0, 180)}…` : text
}

onMounted(async () => { await Promise.all([loadHistory(), loadEval()]) })
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand"><span class="brand-mark">MG</span><div><strong>MineGuard</strong><small>工业安全智能作业 Agent</small></div></div>
      <nav>
        <button :class="{ active: tab === 'console' }" @click="tab = 'console'">Agent Console</button>
        <button :class="{ active: tab === 'history' }" @click="tab = 'history'; loadHistory()">Task History</button>
        <button :class="{ active: tab === 'eval' }" @click="tab = 'eval'; loadEval()">Eval Dashboard</button>
      </nav>
      <div class="live"><i></i> SYNTHETIC DATA</div>
    </header>

    <main v-if="tab === 'console'" class="console-grid">
      <section class="card compose">
        <div class="eyebrow">NEW OPERATION</div>
        <h1>把安全请求交给可审计的工作流</h1>
        <p>规划、结构化查询、知识检索与工具执行逐步可见。高风险变更始终停在人工审批。</p>
        <textarea v-model="query" rows="5" @keydown.ctrl.enter="createTask" />
        <div class="examples">
          <button v-for="item in examples" :key="item" @click="query = item">{{ item }}</button>
        </div>
        <button class="primary" :disabled="busy" @click="createTask">{{ busy ? '正在提交…' : '运行 Agent 任务' }} <span>Ctrl ↵</span></button>
        <p v-if="error" class="error">{{ error }}</p>
      </section>

      <section class="card workflow">
        <div class="section-head"><div><div class="eyebrow">WORKFLOW</div><h2>实时状态</h2></div><span v-if="current" class="state">{{ current.state }}</span></div>
        <div v-if="!current" class="empty">提交任务后，状态迁移与 SSE 事件会出现在这里。</div>
        <template v-else>
          <div class="task-meta"><code>{{ current.taskId }}</code><span :class="['risk', riskClass]">{{ current.plan?.riskLevel ?? '—' }} RISK</span></div>
          <div class="state-track">
            <span v-for="state in ['CREATED','PLANNING','RETRIEVING','ANALYZING','WAITING_APPROVAL','EXECUTING','VERIFYING','COMPLETED']"
              :key="state" :class="{ reached: timeline.some(e => e.type === 'TASK_STATE_CHANGED' && (e.payload as any).to === state) || current.state === state }">{{ state.replace('_',' ') }}</span>
          </div>
          <div v-if="isWaiting" class="approval-panel">
            <div><strong>需要人工审批</strong><p>后端已阻止高风险 Tool，批准后才会签发本次执行授权。</p></div>
            <div><button class="reject" @click="decide('reject')">Reject</button><button class="approve" @click="decide('approve')">Approve & Continue</button></div>
          </div>
          <div class="timeline">
            <article v-for="event in timeline" :key="event.sequence">
              <time>{{ formatTime(event.timestamp) }}</time><i></i>
              <div><strong>{{ event.type }}</strong><code>{{ compact(event.payload) }}</code></div>
            </article>
          </div>
          <div v-if="current.result" class="result">
            <div class="eyebrow">STRUCTURED RESULT</div><h3>{{ current.result.summary }}</h3>
            <ul><li v-for="finding in current.result.findings" :key="finding">{{ finding }}</li></ul>
            <div v-if="current.result.verification.length" class="verified">✓ {{ current.result.verification.join(' · ') }}</div>
          </div>
          <p v-if="current.error" class="error">{{ current.error }}</p>
        </template>
      </section>

      <section class="card evidence-card">
        <div class="section-head"><div><div class="eyebrow">RAG EVIDENCE</div><h2>检索依据</h2></div><span>{{ current?.evidence?.length ?? 0 }} CHUNKS</span></div>
        <div v-if="!current?.evidence?.length" class="empty compact-empty">检索完成后显示带 documentId、chunkId 与 score 的证据。</div>
        <article v-for="item in current?.evidence" :key="item.chunkId" class="evidence">
          <header><strong>{{ item.title }}</strong><span>{{ item.score.toFixed(4) }}</span></header>
          <code>{{ item.documentId }} · {{ item.chunkId }}</code><p>{{ item.content.slice(0, 210) }}…</p>
        </article>
      </section>

      <section class="card tool-card">
        <div class="section-head"><div><div class="eyebrow">TOOL TRACE</div><h2>调用记录</h2></div><span>{{ current?.toolCalls?.length ?? 0 }} CALLS</span></div>
        <div v-if="!current?.toolCalls?.length" class="empty compact-empty">工具名、风险类别、参数、耗时与结构化结果将在此显示。</div>
        <article v-for="call in current?.toolCalls" :key="`${call.toolName}-${call.startedAt}`" class="tool-call">
          <div><i :class="{ ok: call.result.success }"></i><strong>{{ call.toolName }}</strong><span>{{ call.category }}</span></div>
          <code>{{ compact(call.args) }}</code><small>{{ call.result.elapsedMs }} ms · {{ call.result.success ? 'SUCCESS' : call.result.errorCode }}</small>
        </article>
      </section>
    </main>

    <main v-else-if="tab === 'history'" class="wide">
      <div class="page-title"><div><div class="eyebrow">AUDITABLE RUNS</div><h1>Task History</h1></div><button class="secondary" @click="loadHistory">刷新</button></div>
      <section class="card table-card">
        <table><thead><tr><th>创建时间</th><th>请求</th><th>状态</th><th>风险</th><th>Tool</th><th></th></tr></thead>
          <tbody><tr v-for="task in history" :key="task.taskId"><td>{{ new Date(task.createdAt).toLocaleString() }}</td><td>{{ task.userQuery }}</td><td><span class="state">{{ task.state }}</span></td><td>{{ task.plan?.riskLevel ?? '—' }}</td><td>{{ task.toolCalls.length }}</td><td><button class="link" @click="openTask(task)">查看</button></td></tr></tbody>
        </table><div v-if="!history.length" class="empty">暂无任务。</div>
      </section>
    </main>

    <main v-else class="wide">
      <div class="page-title"><div><div class="eyebrow">REPRODUCIBLE BENCHMARK</div><h1>Eval Dashboard</h1></div><span class="state">{{ evaluation?.evaluationMode ?? evaluation?.status ?? 'NOT RUN' }}</span></div>
      <div v-if="evaluation?.retrieval" class="metrics">
        <article><small>RETRIEVAL CASES</small><strong>{{ evaluation.retrieval.caseCount }}</strong><span>Recall@5 {{ percent(evaluation.retrieval.recallAt5) }}</span></article>
        <article><small>AGENT SUCCESS</small><strong>{{ percent(evaluation.agent.taskSuccessRate) }}</strong><span>{{ evaluation.agent.caseCount }} fixed cases</span></article>
        <article><small>APPROVAL ENFORCEMENT</small><strong>{{ percent(evaluation.agent.approvalEnforcementRate) }}</strong><span>backend policy</span></article>
        <article><small>UNSAFE BYPASS</small><strong>{{ evaluation.safety.unsafeActionBypassCount }}/{{ evaluation.safety.caseCount }}</strong><span>adversarial prompts</span></article>
      </div>
      <section v-if="evaluation?.retrieval" class="card eval-detail">
        <h2>Measured, not hard-coded</h2><p>所有卡片来自 <code>docs/eval/latest.json</code>。执行 <code>scripts/run-eval.ps1</code> 会先构建与测试，再重写结果。</p>
        <div class="bars"><label>Recall@1 <i :style="{width: percent(evaluation.retrieval.recallAt1)}"></i><span>{{ percent(evaluation.retrieval.recallAt1) }}</span></label>
          <label>Tool selection <i :style="{width: percent(evaluation.agent.toolSelectionAccuracy)}"></i><span>{{ percent(evaluation.agent.toolSelectionAccuracy) }}</span></label>
          <label>Evidence coverage <i :style="{width: percent(evaluation.agent.ragEvidenceCoverage)}"></i><span>{{ percent(evaluation.agent.ragEvidenceCoverage) }}</span></label></div>
        <p>Latency: p50 {{ evaluation.agent.p50LatencyMs }} ms · p95 {{ evaluation.agent.p95LatencyMs }} ms · Real model: {{ evaluation.realModelEvaluation.status }}</p>
      </section>
      <section v-else class="card empty">{{ evaluation?.message ?? '请先运行评测脚本。' }}</section>
    </main>
  </div>
</template>
