<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { api, ApiError, readEvents } from './api'
import Icon from './Icon.vue'
import TaskReport from './TaskReport.vue'
import { canRun, canReview, roleHint, roleNames, riskNames, stateName, isTerminal, percent, compact } from './presentation'
import type { AgentTask, TaskEvent, User } from './types'

const examples = [
  { title: '违规事件分析', text: '分析最近一周3号采区高频违规事件，并根据安全规程给出处置建议', icon: 'chart' },
  { title: '瓦斯巡查计划', text: '分析最近24小时瓦斯相关告警，并生成巡查计划', icon: 'book' },
  { title: '设备状态查询', text: '查询 camera-03 的设备状态', icon: 'grid' },
]
const navigation = [{ id: 'console', label: '任务工作台', icon: 'grid' }, { id: 'history', label: '任务与审批', icon: 'history' }, { id: 'eval', label: '评测与质量', icon: 'chart' }] as const
const tab = ref<'console' | 'history' | 'eval'>('console'), query = ref(examples[0].text)
const current = ref<AgentTask | null>(null), history = ref<AgentTask[]>([]), timeline = ref<TaskEvent[]>([])
const evaluation = ref<Record<string, any> | null>(null), comparison = ref<Record<string, any> | null>(null)
const realEvaluation = computed(() => comparison.value?.candidate?.agent ? comparison.value.candidate : comparison.value?.baseline)
const busy = ref(false), error = ref(''), streamNotice = ref(''), user = ref<User | null>(null)
const username = ref(''), password = ref(''), approvalReason = ref('')
const newUsername = ref(''), newPassword = ref(''), newRole = ref('OPERATOR'), adminNotice = ref('')
const historyFilter = ref('ALL'), search = ref('')
let stream: AbortController | null = null
let submission: { query: string; key: string } | null = null
let decisionAttempt: { body: string; key: string } | null = null
let sessionVersion = 0
const runnable = computed(() => canRun(user.value)), isWaiting = computed(() => current.value?.state === 'WAITING_APPROVAL')
const canApprove = computed(() => canReview(user.value, current.value))
const waitingCount = computed(() => history.value.filter(t => t.state === 'WAITING_APPROVAL').length)
const filteredHistory = computed(() => history.value.filter(t => (historyFilter.value === 'ALL' || t.state === historyFilter.value) && t.userQuery.toLowerCase().includes(search.value.toLowerCase())))
const steps = ['CREATED', 'PLANNING', 'RETRIEVING', 'ANALYZING', 'WAITING_APPROVAL', 'EXECUTING', 'VERIFYING', 'COMPLETED']
const eventNames: Record<string, string> = { TASK_STATE_CHANGED: '状态更新', PLAN_CREATED: '执行计划已生成', TOOL_STARTED: '开始调用工具', TOOL_FINISHED: '工具返回结果', RAG_RETRIEVED: '知识检索已完成', WAITING_APPROVAL: '等待独立审批', APPROVED: '审批通过', REJECTED: '审批拒绝', VERIFICATION: '独立核验', FINAL_RESULT: '任务结果', ERROR: '执行异常' }
const date = (value: string) => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
const number = (value?: number) => value == null ? '—' : value.toLocaleString('zh-CN')

function handleError(e: unknown) {
  if (e instanceof ApiError && e.status === 401) { clearSession(); error.value = '登录会话已过期，请重新登录。任务会保留在历史记录中。'; return }
  error.value = e instanceof Error ? e.message : String(e)
}
function clearSession() {
  sessionVersion++; stream?.abort(); api.forgetSession()
  user.value = null; current.value = null; history.value = []; timeline.value = []; evaluation.value = null; comparison.value = null
  submission = null; decisionAttempt = null; streamNotice.value = ''; adminNotice.value = ''; tab.value = 'console'
}
async function login() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { user.value = await api.login(username.value, password.value); sessionVersion++; await Promise.all([loadHistory(), loadEval()]) }
  catch (e) { handleError(e) } finally { password.value = ''; busy.value = false }
}
async function logout() {
  if (busy.value) return
  busy.value = true; stream?.abort()
  try { await api.logout() } catch { /* 无论服务是否可达，都清除本地凭据。 */ }
  finally { clearSession(); error.value = ''; busy.value = false }
}
async function createUser() {
  if (busy.value) return
  busy.value = true; adminNotice.value = ''
  try { await api.createUser(newUsername.value, newPassword.value, [newRole.value]); adminNotice.value = '账号创建成功，可以切换该账号登录。'; newUsername.value = '' }
  catch (e) { handleError(e) } finally { newPassword.value = ''; busy.value = false }
}
async function createTask() {
  // 按钮和快捷键共用权限检查，不能通过 Ctrl+Enter 绕过禁用态。
  if (!runnable.value) { error.value = roleHint(user.value); return }
  if (!query.value.trim() || busy.value) return
  busy.value = true; error.value = ''
  try {
    if (!submission || submission.query !== query.value) submission = { query: query.value, key: crypto.randomUUID() }
    const task = await api.createTask(query.value, submission.key)
    submission = null; current.value = task; timeline.value = []; approvalReason.value = ''; connect(task.taskId); await loadHistory()
  } catch (e) { handleError(e) } finally { busy.value = false }
}
function connect(id: string) {
  stream?.abort(); streamNotice.value = ''
  const controller = new AbortController(); stream = controller
  void (async () => {
    let cursor = 0
    while (!controller.signal.aborted) {
      try {
        await readEvents(id, cursor, controller.signal, async event => {
          if (controller.signal.aborted) return
          if (event.sequence > cursor) {
            // 快照读取成功后才推进游标；失败时允许重放，不丢失状态更新。
            const task = await api.task(id)
            if (controller.signal.aborted) return
            current.value = task; timeline.value.push(event); cursor = event.sequence; streamNotice.value = ''
          }
        })
        if (controller.signal.aborted) return
        if (isTerminal(current.value)) { await loadHistory(); return }
      } catch (e) {
        if (controller.signal.aborted) return
        if (e instanceof ApiError && [401, 403, 404].includes(e.status)) { handleError(e); return }
        streamNotice.value = '事件连接暂时中断，正在从已保存的序号恢复…'
      }
      await new Promise(resolve => setTimeout(resolve, 1500))
    }
  })()
}
async function decide(decision: 'approve' | 'reject') {
  if (!canApprove.value || !current.value?.planHash || !approvalReason.value.trim() || busy.value) return
  busy.value = true; error.value = ''
  try {
    const body = JSON.stringify([current.value.taskId, decision, approvalReason.value, current.value.planHash])
    if (!decisionAttempt || decisionAttempt.body !== body) decisionAttempt = { body, key: crypto.randomUUID() }
    current.value = await api.decide(current.value.taskId, decision, approvalReason.value, current.value.planHash, decisionAttempt.key)
    decisionAttempt = null; approvalReason.value = ''; await loadHistory()
  } catch (e) { handleError(e) } finally { busy.value = false }
}
async function loadHistory() {
  const version = sessionVersion
  try { const tasks = await api.tasks(); if (version === sessionVersion) history.value = tasks }
  catch (e) { if (version === sessionVersion) handleError(e) }
}
async function openTask(task: AgentTask) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { current.value = await api.task(task.taskId); tab.value = 'console'; timeline.value = []; approvalReason.value = ''; connect(task.taskId) }
  catch (e) { handleError(e) } finally { busy.value = false }
}
async function loadEval() {
  const version = sessionVersion
  try { const results = await Promise.all([api.eval(), api.realEval()]); if (version === sessionVersion) { evaluation.value = results[0]; comparison.value = results[1] } }
  catch (e) { if (version === sessionVersion) handleError(e) }
}
function navigate(id: typeof tab.value) { tab.value = id; error.value = ''; if (id === 'history') void loadHistory(); if (id === 'eval') void loadEval() }
onUnmounted(() => { sessionVersion++; stream?.abort() })
</script>

<template>
  <div v-if="!user" class="login-layout">
    <section class="login-story">
      <div class="brand"><span class="brand-mark"><Icon name="shield" :size="27" /></span><strong>MineGuard<span>工业安全智能工作台</span></strong></div>
      <div class="story-content"><span class="eyebrow">可追溯 · 可审批 · 可核验</span><h1>让每一次安全决策，<br>都有据可循。</h1><p>从事件分析到行动计划，<br>把复杂任务交给透明、可控的 Agent 工作流。</p>
        <div class="mine-illustration" aria-hidden="true"><div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="mountain back"></div><div class="mountain front"></div><span class="map-point p1"></span><span class="map-point p2"></span><span class="map-point p3"></span><div class="floating-label"><Icon name="shield" /> 人工审批 · 执行护栏</div></div>
      </div><small class="story-foot">MINEGUARD / 安全始于每一步</small>
    </section>
    <section class="login-side"><form class="login-form" @submit.prevent="login"><span class="tag">本地开发环境</span><h2>欢迎回来</h2><p class="muted">登录你的工作账号，继续安全作业。</p>
      <label for="username">用户名</label><input id="username" v-model="username" autocomplete="username" placeholder="输入管理员创建的账号" required maxlength="64" />
      <label for="password">密码</label><input id="password" v-model="password" type="password" autocomplete="current-password" placeholder="输入账号密码" required maxlength="72" />
      <button class="primary login-button" :disabled="busy">{{ busy ? '正在验证…' : '登录工作台' }}<Icon name="arrow" :size="18" /></button>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="login-note"><Icon name="info" :size="18" /><span>运行任务请使用<strong>操作员</strong>账号。管理员负责账号管理，审批员负责独立审批。</span></div>
      <p class="session-note"><Icon name="lock" :size="14" /> 会话仅保存在当前页面，刷新后需重新登录</p>
    </form><small class="login-footer">工业工具连接本地契约环境 · 使用合成事件数据</small></section>
  </div>
  <div v-else class="app-layout">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark"><Icon name="shield" :size="25" /></span><strong>MineGuard<span>工业安全智能工作台</span></strong></div>
      <div class="workspace-label">工作空间 <span>本地</span></div>
      <nav aria-label="主导航"><button v-for="item in navigation" :key="item.id" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" @click="navigate(item.id)"><Icon :name="item.icon" /><span>{{ item.label }}</span><b v-if="item.id === 'history' && waitingCount">{{ waitingCount }}</b></button></nav>
      <div class="sidebar-bottom"><div class="guard-note"><Icon name="shield" /><strong>安全护栏已启用</strong><p>高风险操作必须经过<br>独立审批与执行后核验</p></div><div class="environment"><i></i> 本地契约环境 · 合成数据</div></div>
    </aside>
    <div class="workspace">
      <header class="topbar"><div class="breadcrumb">工作空间 <span>/</span><strong>{{ navigation.find(n => n.id === tab)?.label }}</strong></div><div class="user-menu"><span class="avatar">{{ user.username.slice(0, 1).toUpperCase() }}</span><div><strong>{{ user.username }}</strong><small>{{ user.roles.map(r => roleNames[r] ?? r).join(' · ') }}</small></div><button class="icon-button" title="退出登录 / 切换账号" aria-label="退出登录 / 切换账号" :disabled="busy" @click="logout"><Icon name="logout" :size="18" /></button></div></header>
      <main>
        <div class="page-heading"><div><div class="eyebrow">{{ tab === 'console' ? 'AGENT WORKSPACE' : tab === 'history' ? 'TASK & APPROVAL' : 'EVALUATION' }}</div><h1>{{ tab === 'console' ? '安全作业，从这里开始' : tab === 'history' ? '每个任务，都有完整记录' : '用实测，衡量每一次改进' }}</h1><p>{{ tab === 'console' ? '描述你的目标，查看计划、证据与每一步执行结果。' : tab === 'history' ? '回看执行过程，处理审批，从持久化记录中继续工作。' : '保留原始基线，区分模型调用、工程回归和工具环境。' }}</p></div><span class="outline-tag"><Icon name="shield" :size="14" /> 全流程可审计</span></div>
        <div v-if="error" class="error global-error" role="alert"><Icon name="info" :size="18" /><span>{{ error }}</span><button class="text-button" aria-label="关闭错误提示" @click="error = ''">关闭</button></div>
        <template v-if="tab === 'console'">
          <div class="role-banner" :class="{ restricted: !runnable }"><Icon :name="runnable ? 'check' : 'lock'" :size="19" /><div><strong>{{ user.roles.map(r => roleNames[r] ?? r).join(' / ') }}工作区</strong><span>{{ roleHint(user) }}</span></div><button v-if="!runnable" class="text-button" :disabled="busy" @click="logout">切换账号 <Icon name="arrow" :size="15" /></button></div>
          <div class="console-grid">
            <section class="card compose"><div class="section-head"><div class="section-title"><span class="icon-tile"><Icon name="spark" /></span><div><h2>新建 Agent 任务</h2><p>从一个明确的目标开始</p></div></div><span class="tag soft">自然语言</span></div>
              <label for="task-query" class="field-label">你想处理什么安全任务？</label><textarea id="task-query" v-model="query" rows="5" maxlength="2000" placeholder="例如：分析3号采区违规事件，并根据安全规程给出处置建议" :disabled="!runnable" @keydown.ctrl.enter.prevent="createTask" />
              <div class="input-meta"><span>明确区域、时间和操作目标，能获得更准确的结果</span><span>{{ query.length }}/2000</span></div>
              <div class="example-label">试试这些任务</div><div class="examples"><button v-for="item in examples" :key="item.title" :disabled="!runnable" @click="query = item.text"><Icon :name="item.icon" :size="16" />{{ item.title }}</button></div>
              <button class="primary run-button" :disabled="busy || !runnable || !query.trim()" @click="createTask"><span><Icon name="play" :size="17" />{{ busy ? '正在处理…' : runnable ? '运行 Agent 任务' : '当前账号无执行权限' }}</span><kbd v-if="runnable">Ctrl ↵</kbd><Icon v-else name="lock" :size="17" /></button>
              <p class="helper"><Icon name="info" :size="14" /> 只读任务自动执行，设备启停先规划、后审批。</p>
              <form v-if="user.roles.includes('ADMIN')" class="admin-panel" @submit.prevent="createUser"><h3>创建职责分离账号</h3><label for="new-user">新用户名</label><input id="new-user" v-model="newUsername" autocomplete="off" required minlength="3" maxlength="64" placeholder="至少 3 位" /><label for="new-password">新密码</label><input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72" placeholder="至少 12 位" /><label for="new-role">账号角色</label><select id="new-role" v-model="newRole"><option value="OPERATOR">操作员</option><option value="APPROVER">审批员</option><option value="OBSERVER">审计观察员</option></select><button class="secondary" :disabled="busy">创建账号</button><p class="helper" role="status">{{ adminNotice }}</p></form>
            </section>
            <section class="card workflow"><div class="section-head"><div class="section-title"><span class="icon-tile blue"><Icon name="history" /></span><div><h2>执行工作流</h2><p>{{ current ? '计划与状态实时同步' : '准备就绪，等待新任务' }}</p></div></div><span class="state" :class="current?.state.toLowerCase()">{{ current ? stateName(current.state) : '待开始' }}</span></div>
              <div v-if="!current" class="workflow-empty"><div class="empty-orbit"><Icon name="spark" :size="34" /></div><h3>把任务交给可控的工作流</h3><p>任务提交后，执行步骤将在这里展开。<br>每一次查询、审批和操作都可追溯。</p><div class="empty-flow"><span>理解目标</span><Icon name="arrow" :size="14" /><span>检索证据</span><Icon name="arrow" :size="14" /><span>输出结果</span></div></div>
              <template v-else><div class="task-meta"><code :title="current.taskId">任务 {{ current.taskId.slice(0, 8) }}</code><span class="risk" :class="`risk-${current.plan?.riskLevel.toLowerCase()}`">{{ riskNames[current.plan?.riskLevel ?? ''] ?? '风险评估中' }}</span></div><p class="task-query">{{ current.userQuery }}</p>
                <div class="state-track"><span v-for="(state, index) in steps" :key="state" :class="{ reached: timeline.some(e => e.type === 'TASK_STATE_CHANGED' && e.payload.to === state) || current.state === state, now: current.state === state }"><b>{{ index + 1 }}</b>{{ stateName(state) }}</span></div>
                <div v-if="streamNotice" class="notice" role="status">{{ streamNotice }}</div>
                <div v-if="isWaiting" class="approval-panel"><h3><Icon name="lock" :size="18" /> 等待独立审批</h3><p>高风险工具尚未执行。请核对设备、算法、操作和完整计划。</p><details><summary>查看本次审批绑定的完整计划</summary><code>{{ current.planHash }}</code><pre>{{ JSON.stringify(current.plan, null, 2) }}</pre></details><div v-if="canApprove" class="approval-actions"><label for="approval-reason">审批理由</label><input id="approval-reason" v-model="approvalReason" maxlength="1000" placeholder="填写核对结果与处理理由" /><div><button class="reject" :disabled="busy || !approvalReason.trim()" @click="decide('reject')">拒绝执行</button><button class="primary" :disabled="busy || !approvalReason.trim()" @click="decide('approve')">批准并继续</button></div></div><p v-else class="approval-tip">请由非任务发起人的审批员登录，在「任务与审批」中处理。</p></div>
                <details v-if="current.plan && !isWaiting" class="plan-detail"><summary>查看执行计划 · {{ current.plan.steps.length }} 步</summary><ol><li v-for="step in current.plan.steps" :key="step.id"><strong>{{ step.description }}</strong><code>{{ step.type }} · {{ compact(step.args) }}</code></li></ol></details>
                <TaskReport v-if="current.result" :key="current.taskId" :result="current.result" :tool-calls="current.toolCalls" />
                <p v-if="current.error" class="error">{{ current.error }}</p><div v-if="current.state === 'RECOVERY_REQUIRED'" class="notice">外部操作结果不确定。请人工核验目标状态，不要盲目重新提交。</div>
                <details v-if="timeline.length" class="event-detail"><summary>查看事件时间线 <span>{{ timeline.length }} 条</span></summary><div class="timeline"><article v-for="event in timeline" :key="event.sequence"><time>{{ new Date(event.timestamp).toLocaleTimeString('zh-CN', { hour12: false }) }}</time><i></i><div><strong>{{ eventNames[event.type] ?? event.type }}</strong><code>{{ compact(event.payload) }}</code></div></article></div></details>
              </template>
            </section>
            <section class="card evidence-card"><div class="section-head"><div class="section-title"><span class="icon-tile"><Icon name="book" /></span><div><h2>检索依据</h2><p>为结论提供可追溯的来源</p></div></div><span class="count">{{ current?.evidence?.length ?? 0 }} 条</span></div><div v-if="!current?.evidence?.length" class="small-empty"><Icon name="book" :size="28" /><p>还没有检索依据</p><small>知识检索完成后，来源与相关度将在这里展示</small></div><article v-for="item in current?.evidence" :key="item.chunkId" class="evidence"><header><strong>{{ item.title }}</strong><span>相关度 {{ item.score.toFixed(3) }}</span></header><code>{{ item.documentId }} · {{ item.chunkId }}</code><p>{{ item.content.slice(0, 240) }}{{ item.content.length > 240 ? '…' : '' }}</p><details><summary>查看完整片段</summary><p>{{ item.content }}</p></details></article></section>
            <section class="card tool-card"><div class="section-head"><div class="section-title"><span class="icon-tile blue"><Icon name="tool" /></span><div><h2>工具调用</h2><p>输入、耗时与执行回执</p></div></div><span class="count">{{ current?.toolCalls?.length ?? 0 }} 次</span></div><div v-if="!current?.toolCalls?.length" class="small-empty"><Icon name="tool" :size="28" /><p>还没有工具调用</p><small>每次调用都会留下可核对的结构化记录</small></div><article v-for="(call, index) in current?.toolCalls" :key="index" class="tool-call"><div><span class="call-dot" :class="{ ok: call.result.success }"></span><strong>{{ call.toolName }}</strong><small>{{ call.result.elapsedMs }} ms</small></div><code>{{ compact(call.args) }}</code><details><summary>{{ call.result.success ? '执行成功 · 查看回执' : `执行失败 · ${call.result.errorCode}` }}</summary><pre>{{ JSON.stringify(call.result, null, 2) }}</pre></details></article></section>
          </div>
        </template>
        <section v-else-if="tab === 'history'" class="card table-card"><div class="table-toolbar"><div class="filter-tabs"><button :class="{ selected: historyFilter === 'ALL' }" @click="historyFilter = 'ALL'">全部任务</button><button :class="{ selected: historyFilter === 'WAITING_APPROVAL' }" @click="historyFilter = 'WAITING_APPROVAL'">待审批 <b>{{ waitingCount }}</b></button></div><div class="table-tools"><input v-model="search" aria-label="搜索任务内容" placeholder="搜索任务内容…" /><button class="icon-button" aria-label="刷新任务列表" @click="loadHistory"><Icon name="refresh" :size="18" /></button></div></div><div class="table-scroll"><table><thead><tr><th>任务请求</th><th>创建时间</th><th>执行状态</th><th>风险级别</th><th>调用次数</th><th>操作</th></tr></thead><tbody><tr v-for="task in filteredHistory" :key="task.taskId"><td><strong>{{ task.userQuery }}</strong><code>{{ task.taskId.slice(0, 8) }}</code></td><td>{{ date(task.createdAt) }}</td><td><span class="state" :class="task.state.toLowerCase()">{{ stateName(task.state) }}</span></td><td><span class="risk" :class="`risk-${task.plan?.riskLevel.toLowerCase()}`">{{ riskNames[task.plan?.riskLevel ?? ''] ?? '—' }}</span></td><td>{{ task.toolCalls.length }}</td><td><button class="text-button" :disabled="busy" @click="openTask(task)">{{ task.state === 'WAITING_APPROVAL' ? '查看审批' : '查看详情' }} <Icon name="arrow" :size="14" /></button></td></tr></tbody></table></div><div v-if="!filteredHistory.length" class="small-empty"><Icon name="history" :size="32" /><p>{{ search || historyFilter !== 'ALL' ? '没有符合条件的任务' : '这里将保存你的任务记录' }}</p><small>列表仅显示当前账号有权限查看的最近任务</small></div></section>
        <template v-else>
          <section v-if="realEvaluation?.agent" class="eval-section"><div class="eval-heading"><div><h2>DeepSeek 真实模型评测</h2><p>{{ realEvaluation.provider }} · {{ realEvaluation.planningContract ?? '原始规划器' }} · {{ realEvaluation.agent.caseCount }} 条固定 Agent 用例</p></div><span class="tag soft">{{ realEvaluation.status === 'COMPLETED' ? '已归档' : realEvaluation.status }}</span></div>
            <div class="metrics"><article class="featured"><div><span>Agent 严格成功率</span><Icon name="chart" /></div><strong>{{ percent(realEvaluation.agent.taskSuccessRate) }}</strong><small v-if="comparison?.candidate?.agent">原始基线 {{ percent(comparison.baseline.agent.taskSuccessRate) }} · 评分口径不变</small><small v-else>原始基线，等待新版实测归档</small></article><article><div><span>工具选择准确率</span><Icon name="tool" /></div><strong>{{ percent(realEvaluation.agent.toolSelectionAccuracy) }}</strong><small>工具集合必须完整匹配</small></article><article><div><span>真实 API 请求</span><Icon name="spark" /></div><strong>{{ number(realEvaluation.usage?.attempts) }}<em>次</em></strong><small>含修复与补充用例请求</small></article><article><div><span>审批绕过</span><Icon name="shield" /></div><strong>{{ realEvaluation.safety.unsafeActionBypassCount }}<em>/ {{ realEvaluation.safety.caseCount }}</em></strong><small>仅限本轮对抗用例</small></article></div>
            <div class="eval-grid"><section class="card eval-detail"><div class="section-head"><h2>固定用例前后对比</h2><span class="count">{{ realEvaluation.agent.caseCount }} 条</span></div><div class="score-row"><span>原始版本</span><div class="bar-track"><i class="baseline-bar" :style="{ width: percent(comparison?.baseline?.agent?.taskSuccessRate) }"></i></div><strong>{{ percent(comparison?.baseline?.agent?.taskSuccessRate) }}</strong></div><div class="score-row"><span>当前归档</span><div class="bar-track"><i :style="{ width: percent(realEvaluation.agent.taskSuccessRate) }"></i></div><strong>{{ percent(realEvaluation.agent.taskSuccessRate) }}</strong></div><p class="helper">严格成功 = 工具集合、风险、终态与审批行为同时符合预期；没有放宽评分，也不等于所有实际场景都能通过。</p><div v-if="realEvaluation.supplemental?.caseCount" class="supplemental"><span>新增补充用例</span><strong>{{ percent(realEvaluation.supplemental.taskSuccessRate) }}</strong><small>{{ realEvaluation.supplemental.caseCount }} 条 · 单独计分，非独立盲测</small></div></section><section class="card eval-detail"><h2>Token 与执行耗时</h2><dl class="usage-list"><div><dt>输入 Token</dt><dd>{{ number(realEvaluation.usage?.recordedPromptTokens) }}</dd></div><div><dt>输出 Token</dt><dd>{{ number(realEvaluation.usage?.recordedCompletionTokens) }}</dd></div><div><dt>总 Token</dt><dd>{{ number(realEvaluation.usage?.recordedTotalTokens) }}</dd></div><div><dt>端到端 P50 / P95</dt><dd>{{ number(realEvaluation.agent.p50LatencyMs) }} / {{ number(realEvaluation.agent.p95LatencyMs) }} ms</dd></div></dl><p class="helper">用量来自供应商 usage 回执，不是实时账单。{{ realEvaluation.usage?.usageComplete ? '本轮核心 Token 回执完整。' : '存在未知用量，汇总可能不完整。' }}</p></section></div>
            <details class="card case-results"><summary>逐条查看严格评测结果</summary><div class="case-grid"><div v-for="item in realEvaluation.agent.cases" :key="item.id" :class="{ failed: !item.success }"><strong>{{ item.id }}</strong><span>{{ item.success ? '通过' : '未通过' }}</span><small>{{ item.category }}</small></div></div></details><p class="environment-note"><Icon name="info" :size="16" /> 模型 API 为真实调用；工业工具使用隔离的合成数据和本地测试网关，不代表真实工业设备验收。</p>
          </section><div v-else class="card small-empty"><Icon name="chart" :size="32" /><p>尚无可显示的真实模型评测</p><button class="secondary" @click="loadEval">重新加载</button></div>
          <details v-if="evaluation?.retrieval" class="card deterministic"><summary>历史确定性回归快照 <span>非真实模型指标</span></summary><p>以下数据来自 docs/eval/latest.json，仅用于固定规则回归，不能与真实模型成功率混用。</p><div class="deterministic-metrics"><div><strong>{{ percent(evaluation.agent.taskSuccessRate) }}</strong><small>确定性 Agent 成功率</small></div><div><strong>{{ percent(evaluation.retrieval.recallAt5) }}</strong><small>检索 Recall@5</small></div><div><strong>{{ evaluation.safety.unsafeActionBypassCount }}/{{ evaluation.safety.caseCount }}</strong><small>确定性审批绕过</small></div></div></details>
        </template>
        <footer class="workspace-footer"><span>MineGuard · 每一步都有依据</span><span>计划 → 证据 → 审批 → 执行 → 核验</span></footer>
      </main>
    </div>
  </div>
</template>
