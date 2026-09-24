# 配置即代码（U26）

这一版**不需要配置文件**：所有开关都是环境变量，默认值在代码里（`ServerConfig`、
`StoreFactory`、`Rbac`、`McpMount`）。这是刻意的——配置文件会引入第二套默认值，
于是"改了这个文件为什么没生效"变成一类常驻问题。

所以"配置即代码"在这里的意思是三件事：

1. **变量名是常量**：`Rbac.ENV_RBAC`、`StoreFactory.ENV_URL`、`ServerConfig.ENV_API_KEY`……
   读写都用常量，不写字面量。装配失误（拼错名字）因此是编译错，不是运行期静默失效。
2. **每个环境一份 env 文件**，且**只放差异**：`env/dev.env`、`env/prod.env.example`。
   差异越小，越容易一眼看出"生产与本地到底差在哪"。
3. **静态校验**：`ContainerAssetsTest` 会检查 compose / Dockerfile / env 文件里出现的
   `APLAT_*` 变量**都在代码里被读过**。拼错的变量是最难查的一类配置错误——
   它的表现是"配置没生效"，而没有任何报错。

## 环境差异一览

| 变量 | 本地开发 | 生产 |
|---|---|---|
| `APLAT_DB_URL` | 不设（内存，重启即丢） | 必须设（连不上就启动失败） |
| `APLAT_API_KEY` | 不设（只听 127.0.0.1） | 必须设 |
| `APLAT_HITL` | `allow`（本地不想每次点批准） | `ask` |
| `APLAT_RBAC` | 不设（隐含 admin） | 按角色配置 |
| `APLAT_AUDIT_FILE` | 不设（审计只在内存里留最近 500 条） | 指到一个持久卷 |
| `APLAT_RATE_LIMIT_PER_MIN` | `0`（关掉，别烦自己） | `120` 或按容量定 |

## 用哪份文件

```bash
set -a; . config/env/dev.env; set +a; ./mvnw -q compile exec:java@serve
```

生产用编排层注入（K8s Secret / compose 的 `environment:`），**不要把 prod.env 提交进仓库**——
example 文件只列变量名与说明。
