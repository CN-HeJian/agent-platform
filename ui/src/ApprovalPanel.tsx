import { useCallback, useEffect, useState } from 'react'

/**
 * 人工确认面板（U12/U13）。
 *
 * <p>需要批准的工具（如 {@code shell}）在执行前会停下来等人，这里就是"人"的位置。
 * 四个按钮对应四种决策，各自后果不同：
 * <ul>
 *   <li><b>允许一次</b>：只放行这次调用；下一个命令还会再问。</li>
 *   <li><b>本会话总是允许</b>：本会话内这个工具不再问（对其他会话无效）。
 *       对 {@code shell} 而言等于"这个会话里随便跑命令"——所以按钮上写清楚了粒度。</li>
 *   <li><b>拒绝</b>：不执行，把理由回填给模型让它改道（不是"重试"）。</li>
 *   <li><b>改参数</b>：改完再执行。注意后端会把改后的参数<b>重新过一遍策略</b>——
 *       所以把命令改成危险的那条照样会被拦。</li>
 * </ul>
 *
 * <h2>为什么真相取自 /hitl/pending，而不是从事件流里推导</h2>
 *
 * <p>事件流（{@code /agui/events}）当然包含了 {@code hitl.requested} / {@code hitl.resolved}，
 * 单看也能拼出待办列表。但它拼不出**超时**——超时是"没有事件发生"，
 * 那张卡片会永远挂在界面上等人点。而 {@code /hitl/pending} 是服务端的当下事实：
 * 过期自动消失、别处答过也会消失。
 *
 * <p>所以这里的分工是：**事件流只当"该刷新了"的信号，pending 才是真相**。
 * 顺带还解决了多端同时打开的问题——一个人的回答，另一端的卡片也会跟着消失。
 */
interface Pending {
  requestId: string
  sessionId: string
  tool: string
  args: string
  reason: string
  expiresAt: string
  remainingMs: number
}

export function ApprovalPanel({ threadId, apiKey }: { threadId: string; apiKey?: string }) {
  const [items, setItems] = useState<Pending[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  // 正在改参数的卡片：requestId → 编辑中的参数文本
  const [editing, setEditing] = useState<Record<string, string>>({})

  const authHeaders = useCallback(
    (): Record<string, string> => (apiKey ? { 'X-API-Key': apiKey } : {}),
    [apiKey],
  )

  const refresh = useCallback(async () => {
    try {
      const r = await fetch(`/hitl/pending?sessionId=${encodeURIComponent(threadId)}`, {
        headers: authHeaders(),
      })
      if (!r.ok) {
        throw new Error(r.status === 401 ? '需要 API Key（右上角填）' : `HTTP ${r.status}`)
      }
      const d = (await r.json()) as { interactive: boolean; pending?: Pending[]; note?: string }
      if (!d.interactive) {
        // 后端不等人（APLAT_HITL=allow/deny）——说清楚，别让人以为是自己没配对
        setItems([])
        setError(d.note ?? '当前后端不问人')
        return
      }
      setError(null)
      setItems(d.pending ?? [])
    } catch (e) {
      setItems([])
      setError((e as Error).message)
    }
  }, [threadId, authHeaders])

  useEffect(() => {
    void refresh()

    // 事件流只用来决定"什么时候该重查"：见到任何 HITL 事件就刷一次
    const params = new URLSearchParams({ raw: '1' })
    if (apiKey) params.set('apiKey', apiKey)
    const es = new EventSource(`/agui/events/${encodeURIComponent(threadId)}?${params.toString()}`)
    es.onmessage = (ev: MessageEvent<string>) => {
      if (ev.data.includes('HITL')) void refresh()
    }

    // 兜底轮询：超时不产生事件，得靠它把过期卡片收走；顺带让"剩余 xx s"走动起来
    const timer = window.setInterval(() => void refresh(), 5000)
    return () => {
      es.close()
      window.clearInterval(timer)
    }
  }, [threadId, apiKey, refresh])

  const decide = useCallback(
    async (requestId: string, decision: string, extra: Record<string, unknown> = {}) => {
      setBusy(requestId)
      setToast(null)
      try {
        const r = await fetch(`/hitl/${encodeURIComponent(requestId)}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...authHeaders() },
          body: JSON.stringify({ decision, ...extra }),
        })
        if (r.status === 409) {
          // 已超时或被别人答过——不是错误，是"来晚了"
          setToast('这条已经结束了（超时，或已被另一个窗口回答）')
        } else if (!r.ok) {
          const d = (await r.json().catch(() => ({}))) as { message?: string }
          setToast(`提交失败：${d.message ?? `HTTP ${r.status}`}`)
        } else {
          setToast(`已提交：${decision}`)
          setEditing((prev) => {
            const next = { ...prev }
            delete next[requestId]
            return next
          })
        }
      } catch (e) {
        setToast(`提交失败：${(e as Error).message}`)
      } finally {
        setBusy(null)
        void refresh()
      }
    },
    [authHeaders, refresh],
  )

  return (
    <aside className="approvals">
      <div className="approvals-head">
        <span>待确认</span>
        <span className={`approvals-count${items.length ? ' hot' : ''}`}>{items.length}</span>
        <button type="button" className="ghost" onClick={() => void refresh()}>
          刷新
        </button>
      </div>

      <div className="approvals-body">
        {error && <p className="approvals-empty">{error}</p>}

        {!error && items.length === 0 && (
          <p className="approvals-empty">
            没有等待确认的调用。需要批准的工具（例如 shell）被执行前会出现在这里。
          </p>
        )}

        {items.map((p) => {
          const draft = editing[p.requestId]
          const editingThis = draft !== undefined
          return (
            <div className="approval-card" key={p.requestId}>
              <div className="approval-head">
                <code className="approval-tool">{p.tool}</code>
                <span className="approval-ttl">
                  剩余 {Math.max(0, Math.ceil(p.remainingMs / 1000))}s
                </span>
              </div>
              {p.reason && <div className="approval-reason">{p.reason}</div>}
              {!editingThis && <pre className="approval-args">{p.args}</pre>}
              {editingThis && (
                <textarea
                  className="approval-edit"
                  spellCheck={false}
                  value={draft}
                  onChange={(e) => setEditing({ ...editing, [p.requestId]: e.target.value })}
                />
              )}

              <div className="approval-actions">
                {!editingThis && (
                  <>
                    <button
                      type="button"
                      className="primary"
                      disabled={busy === p.requestId}
                      onClick={() => void decide(p.requestId, 'once')}
                    >
                      允许一次
                    </button>
                    <button
                      type="button"
                      disabled={busy === p.requestId}
                      title={`本会话内 ${p.tool} 不再询问（对其他会话无效）`}
                      onClick={() => void decide(p.requestId, 'always')}
                    >
                      本会话总是允许
                    </button>
                    <button
                      type="button"
                      className="danger"
                      disabled={busy === p.requestId}
                      onClick={() => void decide(p.requestId, 'deny', { reason: '用户在界面上拒绝' })}
                    >
                      拒绝
                    </button>
                    <button
                      type="button"
                      disabled={busy === p.requestId}
                      onClick={() => setEditing({ ...editing, [p.requestId]: p.args })}
                    >
                      改参数…
                    </button>
                  </>
                )}
                {editingThis && (
                  <>
                    <button
                      type="button"
                      className="primary"
                      disabled={busy === p.requestId}
                      title="后端会把改后的参数重新过一遍策略，危险命令照样会被拦"
                      onClick={() => void decide(p.requestId, 'modify', { arguments: draft })}
                    >
                      用改后的参数执行
                    </button>
                    <button
                      type="button"
                      className="ghost"
                      onClick={() =>
                        setEditing((prev) => {
                          const next = { ...prev }
                          delete next[p.requestId]
                          return next
                        })
                      }
                    >
                      取消
                    </button>
                  </>
                )}
              </div>
            </div>
          )
        })}
      </div>

      {toast && <div className="approvals-toast">{toast}</div>}
    </aside>
  )
}
