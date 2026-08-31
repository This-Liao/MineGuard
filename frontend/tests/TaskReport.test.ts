import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import TaskReport from '../src/TaskReport.vue'
import type { TaskResult, ToolCall } from '../src/types'

const result = (): TaskResult => ({
  summary: '已完成事件分析', findings: [], actions: [], executedOperations: [], verification: [], warnings: [],
  report: {
    version: 'grounded-report-v1', summary: '已完成本次安全事件分析。', notes: ['相关度不是置信度。'],
    sections: [
      { key: 'observations', title: '本次发现', statements: [{ text: '共查询到 20 条安全事件。', citationIds: [1] }] },
      { key: 'references', title: '处置参考', statements: [{ text: '检索资料提到：检查电源和网络连通。', citationIds: [2] }] },
    ],
    citations: [
      { id: 1, kind: 'TOOL', title: '事件明细', toolCallIndex: 0 },
      { id: 2, kind: 'KNOWLEDGE', title: '摄像头离线处置', documentId: 'K019', chunkId: 'K019-chunk-1', score: .0466, content: '检查电源和网络连通。\n<img src=x onerror="alert(1)">\n这只是原文文本。' },
    ],
  },
})
const calls: ToolCall[] = [{ toolName: 'query_safety_events', category: 'READ', startedAt: '2026-08-31T09:36:00Z', args: { area: '3号采区' }, result: { success: true, elapsedMs: 3, data: { total: 20 } } }]
let wrapper: VueWrapper
afterEach(() => wrapper?.unmount())
describe('自然语言汇报与引用', () => {
  it('结论和规程分别附带可点击引用', () => {
    wrapper = mount(TaskReport, { props: { result: result(), toolCalls: calls } })
    expect(wrapper.text()).toContain('共查询到 20 条安全事件。')
    expect(wrapper.findAll('.citation-link').map(link => link.text())).toEqual(['[1]', '[2]'])
    expect(wrapper.text()).toContain('本次发现'); expect(wrapper.text()).toContain('处置参考')
    expect(wrapper.find('.citation-panel').exists()).toBe(false)
  })
  it('知识引用展示精确文档片段和原文，不执行 HTML', async () => {
    wrapper = mount(TaskReport, { props: { result: result(), toolCalls: calls }, attachTo: document.body })
    await wrapper.get('[aria-label="查看引用 2：摄像头离线处置"]').trigger('click')
    expect(wrapper.get('.citation-panel').text()).toContain('K019-chunk-1')
    expect(wrapper.get('.citation-panel').text()).toContain('0.0466')
    expect(wrapper.get('blockquote').text()).toContain('<img src=x onerror="alert(1)">')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.citation-panel').attributes('tabindex')).toBe('-1')
    await wrapper.get('[aria-label="关闭引用详情"]').trigger('click')
    expect(wrapper.find('.citation-panel').exists()).toBe(false)
  })
  it('数据引用能核对查询参数与完整工具回执', async () => {
    wrapper = mount(TaskReport, { props: { result: result(), toolCalls: calls } })
    await wrapper.get('[aria-label="查看引用 1：事件明细"]').trigger('click')
    expect(wrapper.get('.citation-panel').text()).toContain('query_safety_events')
    expect(wrapper.get('pre').text()).toContain('3号采区')
    expect(wrapper.get('pre').text()).toContain('"total": 20')
  })
  it('快照更新不关闭用户正在阅读的同任务引用', async () => {
    wrapper = mount(TaskReport, { props: { result: result(), toolCalls: calls } })
    await wrapper.get('[aria-label="查看引用 2：摄像头离线处置"]').trigger('click')
    await wrapper.setProps({ result: result() })
    expect(wrapper.get('.citation-panel').text()).toContain('K019-chunk-1')
  })
  it('未知来源不生成可点击的假引用', () => {
    const value = result(); value.report!.sections[0].statements[0].citationIds = [999]
    wrapper = mount(TaskReport, { props: { result: value, toolCalls: calls } })
    expect(wrapper.find('[aria-label^="查看引用 999"]').exists()).toBe(false)
  })
  it('旧格式和汇报暂不可用时保留可恢复的降级展示', () => {
    const value = result(); value.report = null; value.reportUnavailable = true; value.findings = ['历史原始回执']
    wrapper = mount(TaskReport, { props: { result: value, toolCalls: calls } })
    expect(wrapper.text()).toContain('任务记录未丢失')
    expect(wrapper.get('.legacy-result').attributes('open')).toBeUndefined()
    expect(wrapper.findAll('.citation-link')).toHaveLength(0)
  })
})
