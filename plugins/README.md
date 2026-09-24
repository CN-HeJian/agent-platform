# 插件（U30）

一个插件就是一个 JSON 清单文件。放在 `APLAT_PLUGINS` 指向的目录里，启动时自动加载：

```bash
export APLAT_PLUGINS=./plugins
./mvnw -q compile exec:java@serve
```

## 清单格式

```json
{"name":"qrcode","version":"1.0.0",
 "tools":[
   {"name":"qrcode_make",
    "description":"把文本做成二维码",
    "schema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},
    "commandField":null,
    "approvalRequired":false,
    "handler":{"kind":"stdio","command":"python3 plugins/qrcode.py"}}]}
```

- `handler.command`：**命令写死在清单里**。调用时平台把参数以 JSON 写到子进程的 **stdin**。
- 子进程的 stdout 就是工具结果；非 0 退出码 → 结构化失败（附 stderr）。
- `commandField`：如果工具会把某个参数当命令/脚本执行，**必须**声明它，
  否则策略层的危险命令检查整条失效（与 MCP 是同一条规则）。
- `approvalRequired`：需要人工确认（或者声明了 `commandField`，两者都会自动要求确认）。

## 为什么不把参数拼进命令行

最容易写出的版本是 `python3 qrcode.py "用户输入的文本"`。那样一旦文本里出现了
`; rm -rf /`，它就不再是参数了——**这就是注入本身**。所以这里命令固定、参数走 stdin：
模型的输出永远是 stdin 里的一串字节，没有任何机会变成命令行的一部分。

## 两条硬规则

1. **插件不能覆盖内置工具**，插件之间也不能重名。注册表里"后写覆盖"是静默的，
   于是"我以为我在用受控的 shell"，实际跑的是某个插件给的东西。
2. **加载失败要说清是哪个文件、为什么**。目录里躺着五个文件时，"插件加载失败"没有意义。

## 写一个插件：三行就够

```bash
cat > plugins/upper.json <<'JSON'
{"name":"upper","version":"1.0.0","tools":[
  {"name":"upper","description":"把文本转成大写",
   "schema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},
   "handler":{"kind":"stdio","command":"python3 -c 'import sys,json;print(json.load(sys.stdin)[\"text\"].upper())'"}}]}
JSON
```
