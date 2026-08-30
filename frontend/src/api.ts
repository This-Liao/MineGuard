import type { AgentTask } from './types'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }))
    throw new Error(body.message ?? `HTTP ${response.status}`)
  }
  return response.json() as Promise<T>
}

export const api = {
  createTask: (query: string) => request<AgentTask>('/api/agent/tasks', {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ query }),
  }),
  task: (id: string) => request<AgentTask>(`/api/agent/tasks/${id}`),
  tasks: () => request<AgentTask[]>('/api/agent/tasks'),
  decide: (id: string, decision: 'approve' | 'reject', reason: string) => request<AgentTask>(`/api/tasks/${id}/${decision}`, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ actor: 'web-operator', reason }),
  }),
  eval: () => request<Record<string, any>>('/api/eval/latest'),
}
