/**
 * 前端侧的 API Key 处理（U16 的前端一半）。
 *
 * ## 为什么要有这个文件
 * 后端加了闸之后，前端**必须真的带上 key**，否则 `/agui/run` 直接 401。
 * 而且三条路都要带：
 *   1. `HttpAgent` 的请求头（聊天）
 *   2. `EventSource` 的查询参数（时间线——它不能自定义请求头）
 *   3. `fetch('/tools')` 的请求头（工具卡片）
 * 漏掉任何一条，那个功能区就会静默失效（表现为"没反应"或空列表）。
 *
 * ## 存在哪
 * `localStorage`。这是个**本地开发工具**的取舍：比每次刷新都重新输入好用，
 * 但也就意味着同一台机器的其他脚本能读到它。真正的生产做法是短期令牌 + HttpOnly Cookie，
 * 那属于 U25（RBAC + 密钥管理）的范畴，不该在这里假装已经解决。
 */
const STORAGE_KEY = 'aplat.apiKey'

export function loadApiKey(): string {
  try {
    return window.localStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用——不该因此让页面挂掉
    return ''
  }
}

export function saveApiKey(key: string): void {
  try {
    if (key) {
      window.localStorage.setItem(STORAGE_KEY, key)
    } else {
      window.localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    /* 存不下就算了，本次会话内仍然可用 */
  }
}
