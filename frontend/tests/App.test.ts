import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import App from '../src/App.vue'
import { api, ApiError, readEvents } from '../src/api'

// 组件测试只使用内存夹具，不读取凭据、不访问真实账号、模型或工业服务。
vi.mock('../src/api', () => ({
  ApiError: class extends Error { constructor(public status: number, message: string) { super(message) } },
  api: { login: vi.fn(), logout: vi.fn(), forgetSession: vi.fn(), tasks: vi.fn(), task: vi.fn(), createTask: vi.fn(), decide: vi.fn(), createUser: vi.fn(), eval: vi.fn(), realEval: vi.fn() },
  readEvents: vi.fn(),
}))
const task = (state = 'COMPLETED', ownerId = 'owner') => ({ taskId: 'task-12345678', ownerId, userQuery: '查询设备状态', state, createdAt: '2026-08-31T07:00:00Z', updatedAt: '2026-08-31T07:00:00Z', planHash: 'hash', plan: { intent: '设备查询', riskLevel: state === 'WAITING_APPROVAL' ? 'HIGH' : 'LOW', steps: [] }, toolCalls: [], evidence: [] })
let wrapper: VueWrapper
async function login(roles = ['OPERATOR'], userId = 'u1') {
  vi.mocked(api.login).mockResolvedValue({ userId, username: 'test-user', tenantId: 'test', roles })
  wrapper = mount(App)
  await wrapper.get('#username').setValue('test-user'); await wrapper.get('#password').setValue('offline-fixture')
  await wrapper.get('form').trigger('submit'); await flushPromises()
}
beforeEach(() => {
  vi.mocked(api.tasks).mockResolvedValue([]); vi.mocked(api.task).mockResolvedValue(task())
  vi.mocked(api.createTask).mockResolvedValue(task()); vi.mocked(api.eval).mockResolvedValue({ status: 'NOT_RUN' })
  vi.mocked(api.realEval).mockResolvedValue({ baseline: { status: 'NOT_RUN' }, candidate: { status: 'NOT_RUN' } })
  vi.mocked(api.logout).mockResolvedValue(undefined)
  vi.mocked(readEvents).mockImplementation(async (_id, _after, signal) => { await new Promise<void>(resolve => signal.addEventListener('abort', () => resolve(), { once: true })) })
})
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.resetAllMocks() })
describe('工作台交互回归', () => {
  it('登录表单显示执行所需角色和关联标签', () => {
    wrapper = mount(App)
    expect(wrapper.text()).toContain('运行任务请使用操作员账号')
    expect(wrapper.get('label[for="username"]').text()).toBe('用户名')
    expect(wrapper.get('#password').attributes('type')).toBe('password')
  })
  it.each(['ADMIN', 'APPROVER', 'OBSERVER'])('%s 的按钮和快捷键均不能提交任务', async role => {
    await login([role])
    expect(wrapper.get('.run-button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.role-banner').text()).toContain('操作员')
    await wrapper.get('#task-query').trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(api.createTask).not.toHaveBeenCalled()
  })
  it('操作员能提交，网络失败后复用幂等键', async () => {
    await login()
    vi.mocked(api.createTask).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce(task())
    await wrapper.get('.run-button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('网络中断')
    await wrapper.get('#task-query').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises()
    expect(api.createTask).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.createTask).mock.calls[0][1]).toBe(vi.mocked(api.createTask).mock.calls[1][1])
    expect(wrapper.text()).toContain('任务 task-123')
    expect(readEvents).toHaveBeenCalledTimes(1)
  })
  it('只显示独立审批员可操作的审批按钮', async () => {
    const waiting = task('WAITING_APPROVAL', 'other-user')
    vi.mocked(api.tasks).mockResolvedValue([waiting]); vi.mocked(api.task).mockResolvedValue(waiting)
    await login(['APPROVER'])
    await wrapper.get('nav button:nth-child(2)').trigger('click'); await flushPromises()
    await wrapper.get('tbody .text-button').trigger('click'); await flushPromises()
    expect(wrapper.find('#approval-reason').exists()).toBe(true)
    expect(wrapper.get('.approval-actions .primary').attributes('disabled')).toBeDefined()
    await wrapper.get('#approval-reason').setValue('核对目标后批准')
    vi.mocked(api.decide).mockResolvedValue(task())
    await wrapper.get('.approval-actions .primary').trigger('click'); await flushPromises()
    expect(api.decide).toHaveBeenCalledWith(waiting.taskId, 'approve', '核对目标后批准', 'hash', expect.any(String))
  })
  it('拥有审批角色的发起人仍不能自审', async () => {
    const waiting = task('WAITING_APPROVAL', 'u1')
    vi.mocked(api.tasks).mockResolvedValue([waiting]); vi.mocked(api.task).mockResolvedValue(waiting)
    await login(['OPERATOR', 'APPROVER'])
    await wrapper.get('nav button:nth-child(2)').trigger('click'); await flushPromises()
    await wrapper.get('tbody .text-button').trigger('click'); await flushPromises()
    expect(wrapper.find('#approval-reason').exists()).toBe(false)
    expect(wrapper.text()).toContain('非任务发起人的审批员')
  })
  it('401 会清除本地会话并提示重新登录', async () => {
    await login()
    vi.mocked(api.createTask).mockRejectedValue(new ApiError(401, 'expired'))
    await wrapper.get('.run-button').trigger('click'); await flushPromises()
    expect(wrapper.find('#password').exists()).toBe(true)
    expect(wrapper.text()).toContain('登录会话已过期')
    expect(api.forgetSession).toHaveBeenCalled()
  })
  it('评测页将新版、原始基线和确定性指标分开', async () => {
    const agent = { caseCount: 30, taskSuccessRate: .9667, toolSelectionAccuracy: .9667, p50LatencyMs: 100, p95LatencyMs: 200, cases: [] }
    vi.mocked(api.realEval).mockResolvedValue({ baseline: { agent: { ...agent, taskSuccessRate: .3 } }, candidate: { agent, status: 'COMPLETED', planningContract: 'v2', provider: 'test-model', safety: { unsafeActionBypassCount: 0, caseCount: 20 }, usage: { attempts: 74, recordedTotalTokens: 125091, usageComplete: true } } })
    await login(); await wrapper.get('nav button:nth-child(3)').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('96.67%'); expect(wrapper.text()).toContain('30.00%')
    expect(wrapper.text()).toContain('不代表真实工业设备验收')
  })
  it('快照失败不推进 SSE 游标，重连成功后清除中断提示', async () => {
    vi.useFakeTimers()
    await login()
    const event = { sequence: 8, taskId: 'task-12345678', type: 'TASK_STATE_CHANGED', timestamp: '2026-08-31T07:00:00Z', payload: { to: 'COMPLETED' } }
    vi.mocked(api.task).mockRejectedValueOnce(new Error('快照不可达')).mockResolvedValue(task())
    vi.mocked(readEvents).mockImplementationOnce(async (_id, _after, _signal, callback) => { await callback(event) })
      .mockImplementationOnce(async (_id, _after, _signal, callback) => { await callback(event) })
    await wrapper.get('.run-button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('事件连接暂时中断')
    await vi.advanceTimersByTimeAsync(1500); await flushPromises()
    expect(vi.mocked(readEvents).mock.calls[1][1]).toBe(0)
    expect(wrapper.text()).not.toContain('事件连接暂时中断')
    expect(wrapper.text()).toContain('1 条')
  })
})
