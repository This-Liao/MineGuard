import { describe, expect, it } from 'vitest'
import { canRun, canReview, roleHint, stateName, isTerminal, percent, compact } from '../src/presentation'
import type { User, AgentTask } from '../src/types'

const account = (roles: string[]): User => ({ userId: 'u1', username: '测试账号', tenantId: 'test', roles })
describe('中文状态与权限提示', () => {
  it.each(['ADMIN', 'APPROVER', 'OBSERVER'])('%s 不自动获得任务执行权限', role => {
    expect(canRun(account([role]))).toBe(false)
    expect(roleHint(account([role]))).toContain('操作员')
  })
  it('操作员可运行但不能自行审批', () => {
    expect(canRun(account(['OPERATOR']))).toBe(true)
    expect(canReview(account(['OPERATOR', 'APPROVER']), { ownerId: 'u1' } as AgentTask)).toBe(false)
    expect(canReview(account(['APPROVER']), { ownerId: 'u2' } as AgentTask)).toBe(true)
    expect(canReview(account(['ADMIN']), { ownerId: 'u2' } as AgentTask)).toBe(false)
  })
  it('未知指标不显示为零', () => {
    expect(percent(null)).toBe('—'); expect(percent(NaN)).toBe('—')
    expect(percent(0)).toBe('0.00%'); expect(percent(0.9667)).toBe('96.67%')
    expect(compact(undefined)).toBe('—'); expect(compact('长文本'.repeat(100))).toHaveLength(161)
  })
  it('明确显示恢复状态并保留未知状态名称', () => {
    expect(stateName('RECOVERY_REQUIRED')).toBe('需要人工核验')
    expect(stateName('NEW_STATE')).toBe('NEW_STATE')
    expect(isTerminal({ state: 'RECOVERY_REQUIRED' } as AgentTask)).toBe(true)
    expect(isTerminal(null)).toBe(false); expect(canRun(null)).toBe(false)
  })
})
