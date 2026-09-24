# K8s 清单（U33）

**这些文件只做过静态校验，没有做过 `kubectl apply`**——做这个的时候本机没有集群。
读的时候请按"读一遍、照着改"来用，不要当成"已验证"。

```
k8s/deployment.yaml          应用（非 root、/health 做就绪与存活、密钥走 Secret）
k8s/service.yaml             ClusterIP Service + 审计日志的 PVC
k8s/secret.yaml.example      Secret 的**结构**（不含值）
```

三处值得说的决定：

- **端口只到 ClusterIP。** 这个服务能执行 shell，不该在任何人不注意的时候对外可达。
  要对外就在 Ingress 层加鉴权，而不是把 Service 改成 NodePort/LoadBalancer。
- **就绪探针打 `/health` 而不是探端口。** 只探端口的话，"起来了但连不上库"会被判成可用，
  于是流量照发到一台跑不了的副本上。`/health` 里含 store 与 hitl 的真实状态。
- **审计挂 PVC。** 它是"谁在什么时候调了什么"的唯一凭据，活不过容器重建就没有意义了。
  换副本放 NFS/对象存储是下一步的事。

## 多副本的一个前置说明

现在有两处**假设单实例**，上多副本之前必须先解决（否则副本之间会互相干扰）：

1. `DurableRunner.reclaimOrphans()`：启动时会把所有 `RUNNING` 的任务标成 CRASHED。
   多副本时 B 启动会把 A 正在跑的任务抢走重跑。正确做法是"执行者 id + 心跳租约"。
2. `Scheduler.tick()`：每个副本都会扫调度表，于是同一条调度会被触发多次。
   需要一把分布式锁（或者只让一个副本负责调度）。

这两条都在代码注释里写着，不是意外行为。
