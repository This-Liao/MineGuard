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

export interface AgentTask {
  taskId: string
  userQuery: string
  state: string
  createdAt: string
  updatedAt: string
  plan?: { intent: string; riskLevel: string; steps: Array<{ id: string; type: string; description: string }> }
  toolCalls: ToolCall[]
  evidence: Evidence[]
  approval?: { status: string; decidedBy?: string; reason?: string }
  result?: {
    summary: string
    findings: string[]
    actions: string[]
    executedOperations: string[]
    verification: string[]
    warnings: string[]
  }
  error?: string
}

export interface TaskEvent {
  sequence: number
  taskId: string
  type: string
  timestamp: string
  payload: Record<string, unknown>
}
