import type { AgentTask, User } from './types'

export const stateNames: Record<string, string> = {
  CREATED: '已创建', PLANNING: '生成计划', RETRIEVING: '检索与查询', ANALYZING: '整理证据',
  WAITING_APPROVAL: '等待审批', EXECUTING: '执行操作', VERIFYING: '核验结果', COMPLETED: '已完成',
  FAILED: '执行失败', RECOVERY_REQUIRED: '需要人工核验',
}
export const roleNames: Record<string, string> = { ADMIN: '管理员', OPERATOR: '操作员', APPROVER: '审批员', OBSERVER: '审计观察员' }
export const riskNames: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' }
export const stateName = (state: string) => stateNames[state] ?? state
export const canRun = (user: User | null) => !!user?.roles.includes('OPERATOR')
export const canReview = (user: User | null, task: AgentTask | null) => !!user?.roles.includes('APPROVER') && !!task && user.userId !== task.ownerId
export const isTerminal = (task: AgentTask | null) => !!task && ['COMPLETED', 'FAILED', 'RECOVERY_REQUIRED'].includes(task.state)
export function roleHint(user: User | null) {
  if (!user) return '请先登录后再提交任务。'
  if (canRun(user)) return '可以提交任务；高风险启停仍需另一位审批员确认。'
  if (user.roles.includes('ADMIN')) return '管理员负责账号管理，不自动拥有任务执行权限。请切换操作员账号，或在下方创建操作员。'
  if (user.roles.includes('APPROVER')) return '审批员可在任务历史中处理待审批任务；提交新任务请切换操作员账号。'
  return '当前为只读审计账号；提交任务请切换操作员账号。'
}
export function percent(value?: number | null) { return value == null || !Number.isFinite(value) ? '—' : `${(value * 100).toFixed(2)}%` }
export function compact(value: unknown, max = 160) {
  const text = JSON.stringify(value) ?? '—'
  return text.length > max ? `${text.slice(0, max)}…` : text
}
