import { useEffect, useRef, useState } from 'react'

/**
 * 过程时间线（U07b）：把**原始会话事件**实时摊开。
 *
 * <p>它走后端另一个端点 `/agui/events/{sessionId}`，而**不是**聊天用的 `/agui/run`。
 * 这是刻意的：
 * <ul>
 *   <li>聊天面只该看到"前端需要的事件"（规范 AG-UI），内部记账事件（压缩决策、
 *       输入认领…）被投影器有意过滤掉了；</li>
 *   <li>排查时恰恰需要看那些被过滤掉的东西——"模型为什么忘了"的答案在
 *       {@code context.prepared} 里，不在聊天窗口里。</li>
 * </ul>
 *
 * <p>顺带说一句：这正是 U02 把"事件流面"与"turn 执行面"拆成两条独立入口的回报——
 * 时间线不需要再造一套推送，直接订阅现成的会话日志即可。
 */

interface RawEvent {
  seq: number | null
  type: string
  raw: string
}

/** 内部事件按"是否给前端看"分色：规范事件正常，CUSTOM_* 是平台内部的。 */
function toneOf(type: string): string {
  if (type.startsWith('CUSTOM_')) return 'custom'
  if (type === 'RUN_FINISHED') return 'done'
  if (type === 'RUN_ERROR') return 'error'
  if (type.startsWith('TOOL_CALL')) return 'tool'
  if (type.startsWith('TEXT_MESSAGE')) return 'text'
  return 'plain'
}

export function Timeline({ threadId, apiKey }: { threadId: string; apiKey?: string }) {
  const [events, setEvents] = useState<RawEvent[]>([])
  const [connected, setConnected] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const stickRef = useRef(true)

  useEffect(() => {
    setEvents([])
    // raw=1 是关键：不加它，后端的帧里带 `event:` 字段，浏览器会把它们当**具名事件**派发，
    // es.onmessage 一条都收不到（我第一版就是这样，时间线一直显示"0 条"）。
    // 加 raw=1 让后端写"无名帧"，一切走 onmessage —— 于是新事件类型自动出现，不会静默漏掉。
    const params = new URLSearchParams({ raw: '1' })
    if (apiKey) params.set('apiKey', apiKey)
    const es = new EventSource(`/agui/events/${encodeURIComponent(threadId)}?${params.toString()}`)

    es.onopen = () => setConnected(true)
    es.onerror = () => setConnected(false)

    // 事件名是 AG-UI 名（或 CUSTOM_*）；一律用 onmessage 接，避免逐个类型注册
    es.onmessage = (ev: MessageEvent<string>) => {
      try {
        const parsed = JSON.parse(ev.data) as { type?: string; seq?: number; payload?: unknown }
        setEvents((prev) => [
          ...prev,
          {
            seq: typeof parsed.seq === 'number' ? parsed.seq : null,
            type: parsed.type ?? 'UNKNOWN',
            raw: JSON.stringify(parsed.payload ?? parsed),
          },
        ])
      } catch {
        setEvents((prev) => [...prev, { seq: null, type: 'RAW', raw: ev.data }])
      }
    }

    return () => {
      es.close()
      setConnected(false)
    }
  }, [threadId, apiKey])

  // 贴底：用户往上翻了就别抢滚动位置
  useEffect(() => {
    const box = boxRef.current
    if (box && stickRef.current) {
      box.scrollTop = box.scrollHeight
    }
  }, [events])

  return (
    <aside className="timeline">
      <div className="timeline-head">
        <span className={`dot ${connected ? 'on' : 'off'}`} />
        <span>过程时间线</span>
        <span className="timeline-count">{events.length} 条</span>
        <button type="button" className="ghost" onClick={() => setEvents([])}>
          清空
        </button>
      </div>
      <div
        className="timeline-body"
        ref={boxRef}
        onScroll={(e) => {
          const el = e.currentTarget
          stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24
        }}
      >
        {events.length === 0 && (
          <p className="timeline-empty">
            等待事件… 发一条消息，这里会逐条出现后端产生的原始会话事件
            （含聊天面看不到的内部记账事件）。
          </p>
        )}
        {events.map((e, i) => (
          <div className={`tl-row tl-${toneOf(e.type)}`} key={`${e.seq ?? 'x'}-${i}`}>
            <span className="tl-seq">{e.seq ?? '·'}</span>
            <span className="tl-type">{e.type}</span>
            <span className="tl-raw" title={e.raw}>
              {e.raw}
            </span>
          </div>
        ))}
      </div>
    </aside>
  )
}
