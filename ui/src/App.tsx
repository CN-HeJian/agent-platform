import { useMemo } from 'react'
import { CopilotKit } from '@copilotkit/react-core'
import { CopilotChat } from '@copilotkit/react-ui'
import { HttpAgent } from '@ag-ui/client'

import '@copilotkit/react-ui/styles.css'

/**
 * U07a：复用 CopilotKit 的聊天面，直连我们自己的 AG-UI 后端。
 *
 * ## 为什么是「直连」而不是「runtimeUrl」
 * 常规用法是前端 → CopilotKit runtime（一个 Node 服务）→ 你的 agent。
 * 这里没有 Node runtime，而是把 `HttpAgent` 实例直接交给 provider，
 * 让浏览器直接跟 `POST /agui/run` 说话——少一个进程、少一跳。
 *
 * ## ⚠️ 两条必须知道的限制
 * 1. **这个 prop 名字里就写着 unsafe_dev_only**：它是官方给本地开发用的口子。
 *    生产要走 `selfManagedAgents`（CopilotKit 的付费档）或架一个 runtime 做代理。
 * 2. 直连意味着**鉴权、CORS、限流全部由我们自己的端点负责**——
 *    provider 的 headers 在这里不会自动帮你带（见下）。
 *
 * 这两条都不是"以后再说"的细节：它们正是 D6（前端复用 vs 自研）需要重新权衡的地方。
 *
 * ## 后端必须守的纪律
 * 协议契约一旦破了，前端就白接。所以 U08 的 `AgUiProjector` 有 13 个用例钉住事件形状。
 */
const AGENT_PATH = '/agui/run'

function agentUrl(): string {
  // 允许用 VITE_AGENT_URL 指向别处（比如把前端单独部署、后端在另一台机器）
  const override = import.meta.env.VITE_AGENT_URL as string | undefined
  if (override) return override
  // 同源部署（由 Java 服务托管 /ui/）→ 直接用当前站点；开发态走 vite 代理
  return new URL(AGENT_PATH, window.location.origin).toString()
}

export function App() {
  const url = useMemo(agentUrl, [])

  const agents = useMemo(
    () => ({
      default: new HttpAgent({ url }),
    }),
    [url],
  )

  return (
    <CopilotKit agents__unsafe_dev_only={agents}>
      <div className="page">
        <header className="page-head">
          <div>
            <h1>agent-platform · 会话</h1>
            <p className="sub">
              CopilotKit 聊天面 · AG-UI 直连 <code>{url}</code>
            </p>
          </div>
          <div className="links">
            <a href="/health" target="_blank" rel="noreferrer">
              健康检查
            </a>
            <a href="/kernel" target="_blank" rel="noreferrer">
              装配清单
            </a>
            <a href="/" target="_blank" rel="noreferrer">
              调试控制台
            </a>
          </div>
        </header>
        <main className="page-body">
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
        </main>
      </div>
    </CopilotKit>
  )
}
