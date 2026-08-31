import type { AgentTask, AgentReport, TaskEvent, User } from './types'

// 凭据只保留在当前页面内存；不写入 URL、浏览器存储或日志。
let token = ''
export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}
async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(url, { ...init, headers })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }))
    throw new ApiError(response.status, body.message ?? `HTTP ${response.status}`)
  }
  return response.json() as Promise<T>
}
const json = (body: unknown, key?: string): RequestInit => ({
  method: 'POST', headers: { 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) },
  body: JSON.stringify(body),
})

async function taskWithReport(id: string): Promise<AgentTask> {
  const task = await request<AgentTask>(`/api/agent/tasks/${id}`)
  // 历史检查点没有汇报字段时，只读转换已有回执，不重新创建任务或调用模型。
  if (task.result && !task.result.report) {
    try { task.result.report = await request<AgentReport>(`/api/agent/tasks/${id}/report`) }
    catch (e) {
      if (e instanceof ApiError && [401, 403, 404].includes(e.status)) {
        // 旧后端没有该路由时允许降级，认证失效仍交给页面处理。
        if (e.status !== 404) throw e
      }
      task.result.reportUnavailable = true
    }
  }
  return task
}
export const api = {
  async login(username: string, password: string) {
    const result = await request<{ accessToken: string; user: User }>('/api/auth/login', json({ username, password }))
    token = result.accessToken
    return result.user
  },
  async logout() { try { await request('/api/auth/logout', json({})) } finally { token = '' } },
  forgetSession() { token = '' },
  createTask: (query: string, key: string) => request<AgentTask>('/api/agent/tasks', json({ query }, key)),
  task: taskWithReport,
  tasks: () => request<AgentTask[]>('/api/agent/tasks'),
  decide: (id: string, decision: 'approve' | 'reject', reason: string, planHash: string, key: string) =>
    request<AgentTask>(`/api/tasks/${id}/${decision}`, json({ reason, planHash }, key)),
  eval: () => request<Record<string, any>>('/api/eval/latest'),
  realEval: () => request<Record<string, any>>('/api/eval/comparison'),
  createUser: (username: string, password: string, roles: string[]) =>
    request<User>('/api/admin/users', json({ username, password, roles })),
}

// 原生 EventSource 不能携带 Bearer 请求头，使用 fetch 解析流并从数据库序号恢复。
export async function readEvents(id: string, after: number, signal: AbortSignal,
  onEvent: (event: TaskEvent) => Promise<void>): Promise<void> {
  const response = await fetch(`/api/agent/tasks/${id}/stream`, {
    headers: { Authorization: `Bearer ${token}`, 'Last-Event-ID': String(after) }, signal,
  })
  if (!response.ok) throw new ApiError(response.status, `事件连接失败（${response.status}）`)
  if (!response.body) throw new Error('浏览器不支持流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // 允许 CRLF 跨网络分片，保留尚未结束的消息。
      let match: RegExpExecArray | null
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).replace(/^ /, '')).join('\n')
        if (data) await onEvent(JSON.parse(data) as TaskEvent)
      }
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
