import { afterEach, expect, it, vi } from 'vitest'
import { api, ApiError, readEvents } from '../src/api'

afterEach(() => { api.forgetSession(); vi.unstubAllGlobals() })
it('SSE 支持跨分片 CRLF、多行数据，并携带游标和 Bearer 头', async () => {
  const event = { sequence: 8, taskId: 'fixture', type: 'TASK_STATE_CHANGED', timestamp: '2026-08-31T07:00:00Z', payload: { to: 'COMPLETED' } }
  const encoder = new TextEncoder()
  const body = new ReadableStream({ start(controller) {
    for (const text of [': ping\r', '\n\r\n', 'data: ' + JSON.stringify(event).slice(0, 20), JSON.stringify(event).slice(20) + '\r', '\n\r', '\n']) controller.enqueue(encoder.encode(text))
    controller.close()
  } })
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: 'offline-token', user: {} }), { status: 200 })).mockResolvedValueOnce(new Response(body))
  vi.stubGlobal('fetch', fetcher)
  await api.login('fixture', 'invalid-fixture-password')
  const events: unknown[] = []
  await readEvents('fixture', 7, new AbortController().signal, async event => { events.push(event) })
  expect(events).toEqual([event])
  expect(fetcher.mock.calls[1][1].headers).toEqual({ Authorization: 'Bearer offline-token', 'Last-Event-ID': '7' })
})
it('SSE 对未授权响应保留状态码', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
  await expect(readEvents('fixture', 0, new AbortController().signal, async () => {})).rejects.toBeInstanceOf(ApiError)
})
it('退出请求失败时依然清除本地令牌', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: 'offline-token', user: {} })))
    .mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(new Response('[]'))
  vi.stubGlobal('fetch', fetcher)
  await api.login('fixture', 'invalid-fixture-password')
  await expect(api.logout()).rejects.toThrow('offline')
  await api.tasks()
  expect(fetcher.mock.calls[2][1].headers.has('Authorization')).toBe(false)
})

it('旧任务仅额外 GET 汇报，不重新提交任务', async () => {
  const old = { taskId: 'old', state: 'COMPLETED', updatedAt: '2026-08-31T09:00:00Z', result: { summary: '旧结果', findings: [] } }
  const report = { version: 'grounded-report-v1', summary: '自然语言', sections: [], citations: [], notes: [] }
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(old))).mockResolvedValueOnce(new Response(JSON.stringify(report)))
  vi.stubGlobal('fetch', fetcher)
  const actual = await api.task('old')
  expect(actual.result?.report).toEqual(report)
  expect(actual.updatedAt).toBe(old.updatedAt)
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/agent/tasks/old', '/api/agent/tasks/old/report'])
  expect(fetcher.mock.calls.every(call => call[1].method == null)).toBe(true)
})
it('新任务已有汇报时不重复请求；旧汇报 401 不被吞掉', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ result: { report: { version: 'v1' } } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ result: {} }))).mockResolvedValueOnce(new Response('{"message":"expired"}', { status: 401 }))
  vi.stubGlobal('fetch', fetcher)
  await api.task('new'); expect(fetcher).toHaveBeenCalledTimes(1)
  await expect(api.task('old')).rejects.toMatchObject({ status: 401 })
})
it('汇报失败不丢失旧任务结果', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ result: { summary: '旧结果' } })))
    .mockResolvedValueOnce(new Response('{"message":"unavailable"}', { status: 503 }))
  vi.stubGlobal('fetch', fetcher)
  const actual = await api.task('old')
  expect(actual.result?.summary).toBe('旧结果'); expect(actual.result?.reportUnavailable).toBe(true)
})
