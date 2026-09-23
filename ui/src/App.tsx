import { useCallback, useEffect, useMemo, useState } from 'react'
import { CopilotKit } from '@copilotkit/react-core'
import { CopilotChat } from '@copilotkit/react-ui'
import { HttpAgent } from '@ag-ui/client'

import { ToolCard, type ToolInfo } from './ToolCard'
import { Timeline } from './Timeline'
import './styles.css'

import '@copilotkit/react-ui/styles.css'

/**
 * U07a + U07b：CopilotKit 聊天面（直连自己的 AG-UI 后端）+ 工具卡片 + 过程时间线。
 *
 * ## 为什么是「直连」而不是「runtimeUrl」
 * 常规用法是前端 → CopilotKit runtime（一个 Node 服务）→ 你的 agent。
 * 这里没有 Node runtime，而是把 `HttpAgent` 实例直接交给 provider，
 * 让浏览器直接跟 `POST /agui/run` 说话——少一个进程、少一跳。
 *
 * ## ⚠️ 两条必须知道的限制
 * 1. **这个 prop 名字里就写着 unsafe_dev_only**：它是官方给本地开发用的口子。
 *    生产要走 `selfManagedAgents`（CopilotKit 的付费档）或架一个 runtime 做代理。
 * 2. 直连意味着**鉴权、CORS、限流全部由我们自己的端点负责**。
 */
const AGENT_PATH = '/agui/run'

function agentUrl(): string {
  const override = import.meta.env.VITE_AGENT_URL as string | undefined
  if (override) return override
  return new URL(AGENT_PATH, window.location.origin).toString()
}

function newThreadId(): string {
  return `t-${Math.random().toString(36).slice(2, 10)}`
}

export function App() {
  const url = useMemo(agentUrl, [])
  const [threadId, setThreadId] = useState(newThreadId)
  const [tools, setTools] = useState<ToolInfo[]>([])
  const [showTimeline, setShowTimeline] = useState(true)

  // 工具清单从后端拿，而不是把 'shell'/'echo' 写死在界面里 —— 加一个新工具，卡片自动跟上
  useEffect(() => {
    let alive = true
    fetch('/tools')
      .then((r) => r.json())
      .then((d: { tools: ToolInfo[] }) => {
        if (alive) setTools(d.tools ?? [])
      })
      .catch(() => {
        /* 拿不到就不渲染工具卡片，聊天本身不受影响 */
      })
    return () => {
      alive = false
    }
  }, [])

  // 匹配规则是精确工具名，所以每个工具一条渲染器。
  // 这里不写类型断言：让 TS 在传 prop 时校验（写错形状会当场报错，而不是运行时静默不渲染）
  const renderToolCalls = useMemo(() => tools.map((t) => ({ name: t.name, render: ToolCard })), [tools])

  const agents = useMemo(
    () => ({
      default: new HttpAgent({ url }),
    }),
    [url],
  )

  const resetThread = useCallback(() => {
    setThreadId(newThreadId())
  }, [])

  return (
    <CopilotKit
      agents__unsafe_dev_only={agents}
      renderToolCalls={renderToolCalls}
      threadId={threadId}
    >
      <div className="page">
        <header className="page-head">
          <div>
            <h1>agent-platform · 会话</h1>
            <p className="sub">
              CopilotKit 聊天面 · AG-UI 直连 <code>{url}</code> · thread <code>{threadId}</code>
            </p>
          </div>
          <div className="links">
            <button type="button" className="ghost" onClick={resetThread}>
              新会话
            </button>
            <button type="button" className="ghost" onClick={() => setShowTimeline((v) => !v)}>
              {showTimeline ? '隐藏时间线' : '显示时间线'}
            </button>
            <a href="/tools" target="_blank" rel="noreferrer">
              工具清单
            </a>
            <a href="/health" target="_blank" rel="noreferrer">
              健康检查
            </a>
            <a href="/" target="_blank" rel="noreferrer">
              调试控制台
            </a>
          </div>
        </header>

        <main className={`page-body${showTimeline ? ' with-timeline' : ''}`}>
          <section className="chat-wrap">
            <CopilotChat
              className="chat"
              labels={{
                title: 'Agent',
                initial:
                  '说点什么试试。离线模式下后端用的是脚本化模型，所以回复是固定剧本；' +
                  '配上 APLAT_LLM_* 环境变量重启后端，它就会真的思考了。',
                placeholder: '例如：用 shell 打印当前目录',
              }}
            />
          </section>
          {showTimeline && <Timeline threadId={threadId} />}
        </main>

        <footer className="page-foot">
          工具卡片 {tools.length ? `已注册 ${tools.length} 个（${tools.map((t) => t.name).join(' · ')}）` : '加载中…'}
          {tools.some((t) => t.executesCommands) && ' · 标注"会执行命令"的工具受策略拦截'}
        </footer>
      </div>
    </CopilotKit>
  )
}
