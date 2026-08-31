export interface Evidence {
  documentId: string
  title: string
  chunkId: string
  score: number
  content: string
}

export interface ToolCall {
  toolName: string
  args: Record<string, unknown>
  category: string
  startedAt: string
  result: { success: boolean; data: unknown; errorCode?: string; errorMessage?: string; elapsedMs: number }
}

export interface AgentReport {
  version: string
  summary: string
  sections: Array<{ key: string; title: string; statements: Array<{ text: string; citationIds: number[] }> }>
  citations: Array<{
    id: number
    kind: 'KNOWLEDGE' | 'TOOL'
    title: string
    documentId?: string | null
    chunkId?: string | null
    score?: number | null
    content?: string | null
    toolCallIndex?: number | null
  }>
  notes: string[]
}

export interface TaskResult {
  summary: string
  findings: string[]
  actions: string[]
  executedOperations: string[]
  verification: string[]
  warnings: string[]
  report?: AgentReport | null
  reportUnavailable?: boolean
}

export interface AgentTask {
  taskId: string
  ownerId: string
  planHash?: string
  userQuery: string
  state: string
  createdAt: string
  updatedAt: string
  plan?: { intent: string; riskLevel: string; steps: Array<{ id: string; type: string; description: string; args: Record<string, unknown> }> }
  toolCalls: ToolCall[]
  evidence: Evidence[]
  approval?: { status: string; decidedBy?: string; reason?: string }
  result?: TaskResult
  error?: string
}

export interface TaskEvent {
  sequence: number
  taskId: string
  type: string
  timestamp: string
  payload: Record<string, unknown>
}

export interface User {
  userId: string
  username: string
  tenantId: string
  roles: string[]
}
