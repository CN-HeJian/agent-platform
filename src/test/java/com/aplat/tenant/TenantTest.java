package com.aplat.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.auth.Rbac;
import com.aplat.seam.SessionEvent;
import com.aplat.seam.Store;
import com.aplat.session.EventSourcedSessionLog;
import com.aplat.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U33 验收：租户隔离。
 *
 * <p>最关键的一条：**两个租户用同一个 sessionId，数据不能混**。只隔离命名空间是不够的——
 * 事件表是全局的，而 sessionId 是调用方给的（经常是 `s-1` 这种可猜的东西）。
 */
class TenantTest {

    private final InMemoryStore raw = new InMemoryStore();

    @AfterEach
    void tearDown() {
        Tenant.clear();
    }

    @Test
    @DisplayName("同名会话在不同租户里互不干扰（事件 + 键值都不混）")
    void sameSessionIdIsIsolated() {
        Tenant.set("acme");
        Store a = new TenantStore(raw, Tenant::current);
        var logA = new EventSourcedSessionLog(a);
        logA.append("s-1", "input.claimed", Map.of("text", "A 的会话"));

        Tenant.set("globex");
        Store b = new TenantStore(raw, Tenant::current);
        var logB = new EventSourcedSessionLog(b);
        logB.append("s-1", "input.claimed", Map.of("text", "B 的会话"));

        // 读也按"当前"租户解析，所以看 A 的数据必须先切回 A——这正是 ThreadLocal 作用域的含义
        Tenant.set("acme");
        assertEquals(1, logA.events("s-1").size(), "A 只该看到自己的那一条");
        assertEquals("A 的会话", logA.events("s-1").get(0).get("text"));
        Tenant.set("globex");
        assertEquals(1, logB.events("s-1").size());
        assertEquals("B 的会话", logB.events("s-1").get(0).get("text"));

        // 库里确实是两条，只是各自看不到对方的
        assertEquals(2, raw.sessionIds().size(), "底层两个 key 都在，前缀不同");
    }

    @Test
    @DisplayName("sessionIds() 只列出本租户的，且剥掉前缀")
    void sessionIdsOnlyMine() {
        Tenant.set("acme");
        var a = new TenantStore(raw, Tenant::current);
        new EventSourcedSessionLog(a).append("s-1", "x", Map.of());
        Tenant.set("globex");
        var b = new TenantStore(raw, Tenant::current);
        new EventSourcedSessionLog(b).append("s-2", "x", Map.of());

        assertEquals(List.of("s-2"), b.sessionIds(), "不带前缀，且只有自己的");
    }

    @Test
    @DisplayName("键值也按租户隔离（任务、调度、工作区都在这一层）")
    void keyValueIsIsolated() {
        Tenant.set("acme");
        new TenantStore(raw, Tenant::current).put("task", "t1", "{\"state\":\"RUNNING\"}");
        Tenant.set("globex");
        var b = new TenantStore(raw, Tenant::current);

        assertTrue(b.get("task", "t1").isEmpty(), "不该读到别的租户的任务");
        assertTrue(b.all("task").isEmpty());
    }

    @Test
    @DisplayName("没有租户上下文时落到 default 空间——绝不能落到「全局」（那等于不隔离）")
    void missingTenantFallsBackToDefault() {
        Tenant.clear();
        Store s = new TenantStore(raw, Tenant::current);
        new EventSourcedSessionLog(s).append("s-x", "x", Map.of());
        assertTrue(raw.sessionIds().stream().anyMatch(id -> id.startsWith(Tenant.DEFAULT + "::")),
                raw.sessionIds().toString());
    }

    @Test
    @DisplayName("身份里带租户：alice@acme 解析出 name=alice / tenant=acme")
    void principalCarriesTenant() {
        var rbac = Rbac.fromEnv(Map.of(Rbac.ENV_RBAC,
                "alice@acme:admin:keyA;bob@globex:viewer:keyB;carol:operator:keyC"));
        assertTrue(rbac.multiTenant());

        var alice = rbac.identify("keyA").orElseThrow();
        assertEquals("alice", alice.id());
        assertEquals("acme", alice.tenant());
        assertEquals("admin", alice.role().name());

        assertEquals("globex", rbac.identify("keyB").orElseThrow().tenant());
        // 不带 @ 的归到默认租户：不分区的部署不用改配置
        assertEquals(Tenant.DEFAULT, rbac.identify("keyC").orElseThrow().tenant());
        assertFalse(Rbac.fromEnv(Map.of(Rbac.ENV_RBAC, "carol:operator:keyC")).multiTenant());
    }

    @Test
    @DisplayName("K8s 清单：与 Dockerfile / 代码里的常量一致（静态校验，本机没有集群）")
    void k8sManifestsAreConsistent() throws Exception {
        Path dir = Path.of("k8s");
        String deployment = Files.readString(dir.resolve("deployment.yaml"), StandardCharsets.UTF_8);
        String service = Files.readString(dir.resolve("service.yaml"), StandardCharsets.UTF_8);
        String secret = Files.readString(dir.resolve("secret.yaml.example"), StandardCharsets.UTF_8);

        // 端口必须与代码/镜像一致
        assertTrue(deployment.contains("containerPort: 8787"), "端口要和 ServerConfig.DEFAULT_PORT 对上");
        assertTrue(service.contains("port: 8787"));
        // 非 root 与 Dockerfile 的 uid 一致
        assertTrue(deployment.contains("runAsNonRoot: true"));
        assertTrue(deployment.contains("runAsUser: 10001"), "要和 Dockerfile 里的 aplat 用户一致");
        // 探针打 /health，而不是探端口
        assertTrue(deployment.contains("httpGet: { path: /health"));
        // 密钥走 Secret，不写进清单
        assertTrue(deployment.contains("secretKeyRef"));
        assertFalse(deployment.contains("value: \"sk-"), "清单里不该出现密钥原文");
        // Secret 的 example 只列结构，不含值
        assertTrue(secret.contains("api-key: \"\""), "example 里必须留空，否则会被直接抄进生产");
        assertTrue(secret.contains("rbac: \"\""));
        assertFalse(secret.matches("(?s).*(api-key|rbac|llm-api-key):\\s*\"[^\"]+\".*"), secret);
        // 服务只到 ClusterIP：这个服务能执行 shell
        assertTrue(service.contains("type: ClusterIP"));

        assertTrue(Files.readString(dir.resolve("README.md"), StandardCharsets.UTF_8)
                .contains("没有做过"), "README 要写明没有做过 kubectl apply");
    }

    @Test
    @DisplayName("事件里的会话 id 在库里带前缀——这一点要写清，否则直接查库会以为数据丢了")
    void storedSessionIdIsPrefixed() {
        Tenant.set("acme");
        var store = new TenantStore(raw, Tenant::current);
        new EventSourcedSessionLog(store).append("s-1", "x", Map.of());
        List<SessionEvent> inDb = raw.events(Tenant.scope("acme", "s-1"), 0);
        assertEquals(1, inDb.size());
        assertEquals("acme::s-1", inDb.get(0).sessionId(), "直接查库时看到的是带前缀的");
    }
}
