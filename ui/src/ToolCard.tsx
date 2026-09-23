import { useMemo } from 'react'
import { CopilotKit } from '@copilotkit/react-core'

/**
 * 工具调用卡片（U07b）。
 *
 * ## 为什么需要它
 * 后端的 AG-UI 事件里 `TOOL_CALL_START/ARGS/END/RESULT` 一直是齐的（有测试与 curl 抓包为证），
 * 但 CopilotKit 默认**不知道该怎么画一个工具调用**——不注册渲染器，界面上就什么都看不到，
 * 只能读到工具的文字结果。这不是后端问题。
 *
 * ## 契约是编译器给的，不是文档给的
 * CopilotKit 各框架文档写法不一致，所以这里的类型**全部从装好的包里推出来**：
 * `React.ComponentProps<typeof CopilotKit>['renderToolCalls']` → 渲染器 →
 * `React.ComponentProps<渲染器.render>` → 组件的 props。
 *
 * 中途踩了两脚，都值得记住：
 * 1. `status` 是**枚举**（`ToolCallStatus`）而不是字符串字面量——手写形状必翻车；
 * 2. 那个枚举**并没有从公开入口导出**，所以根本别去引用它的成员。
 *    改用联合类型自己的结构判别：`result !== undefined` ⇔ 这次调用已经结束。
 *    这比依赖枚举名更稳——枚举改了、或者它本来就不是字符串枚举，这里都不会坏。
 *
 * ## 两条运行时的硬约束
 * 1. **匹配规则是精确工具名**（运行时：`rc.name === toolCall.function.name`），
 *    所以每个工具要一条渲染器 —— 工具清单从后端 `GET /tools` 拿，不硬编码名字。
 * 2. 一次调用是渐进的：先有参数、再有结果；卡片按"有没有结果"分两态显示。
 */

/** 与后端 GET /tools 对齐。 */
export interface ToolInfo {
  name: string
  description: string
  approvalRequired: boolean
  executesCommands: boolean
  commandField: string | null
}

type KitProps = React.ComponentProps<typeof CopilotKit>
type ToolRender = NonNullable<KitProps['renderToolCalls']>[number]['render']

/** 直接从 CopilotKit 的类型推导，不手写。 */
export type ToolCallProps = React.ComponentProps<ToolRender>

/** 结果可能是字符串，也可能已经是对象（取决于客户端如何解析 TOOL_CALL_RESULT）。 */
function asText(result: unknown): string {
  if (result == null) return ''
  if (typeof result === 'string') return result
  if (typeof result === 'object' && 'content' in (result as Record<string, unknown>)) {
    return String((result as Record<string, unknown>).content ?? '')
  }
  return JSON.stringify(result, null, 2)
}

/** 与后端约定：工具失败时内容以 `ERROR[错误码]` 开头（模型看到的就是这个）。 */
function looksFailed(text: string): boolean {
  return text.includes('ERROR[')
}

export function ToolCard(props: ToolCallProps) {
  const { name, args, result } = props

  const argText = useMemo(() => {
    try {
      return JSON.stringify(args ?? {}, null, 2)
    } catch {
      return String(args)
    }
  }, [args])

  const resultText = useMemo(() => asText(result), [result])

  // 结构判别：有结果 = 这次调用结束了。不用枚举名做判断（那个枚举没导出）
  const complete = result !== undefined
  const failed = complete && looksFailed(resultText)

  // 折叠时给一行摘要（取第一个参数值），省得每次都要展开
  const summary = useMemo(() => {
    try {
      const first = Object.values((args ?? {}) as Record<string, unknown>)[0]
      return first === undefined ? '' : String(first)
    } catch {
      return ''
    }
  }, [args])

  return (
    <details className={`tool-card${failed ? ' tool-card--failed' : ''}`}>
      <summary className="tool-head">
        <span className="tool-name">{name}</span>
        <span className="tool-summary">{summary}</span>
        <span className={`tool-status tool-status--${complete ? 'complete' : 'running'}`}>
          {complete ? '已完成' : '执行中'}
        </span>
        {failed && <span className="tool-flag">被拒 / 失败</span>}
      </summary>

      <div className="tool-body">
        <div className="tool-section">
          <div className="tool-label">参数</div>
          <pre>{argText}</pre>
        </div>
        {complete && (
          <div className="tool-section">
            <div className="tool-label">结果</div>
            <pre>{resultText || '(空)'}</pre>
          </div>
        )}
      </div>
    </details>
  )
}
