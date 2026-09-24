package com.aplat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.ToolSpec;
import com.aplat.tools.DefaultToolPolicy;
import com.aplat.tools.ToolPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U25 验收：角色授权 + 密钥脱敏。
 *
 * <p>这个类里最该看的三条：**失败时关门**（没有身份的会话一个工具都不许用）、
 * **"能干活"与"能批准"是两个权限**、以及**密钥不随请求进审计**。
 */
class RbacTest {

    private static final ToolSpec SHELL = ToolSpec.executing("shell", "跑命令", "{}", "command");
    private static final ToolSpec ADD = ToolSpec.of("add", "算加法", "{}");
    private static final ToolSpec READ_FILE = ToolSpec.of("read_file", "读文件", "{}");

    private static Rbac configured() {
        return Rbac.fromEnv(Map.of(Rbac.ENV_RBAC,
                "alice:admin:keyA;bob:viewer:keyB;carol:operator:keyC;dave:runner:keyD"));
    }

    // ------------------------------------------------------------------ 角色

    @Test
    @DisplayName("角色匹配：精确名，或以 * 结尾的前缀；* 只允许在末尾")
    void roleMatching() {
        Role viewer = Role.viewer();
        assertTrue(viewer.permits("read_file"));
        assertTrue(viewer.permits("get_weather"));
        assertTrue(viewer.permits("add"));
        assertFalse(viewer.permits("shell"), "viewer 连 shell 都不该有——这应当是配置层面一眼可见的事实");
        assertTrue(viewer.permits("read"),
                "read* 是前缀匹配，所以它确实包含 read 本身——这一点要写出来，\n"
                        + "否则将来有人会以为 * 是「至少再多一个字符」");
        assertTrue(viewer.permits("read_and_delete"),
                "read* 会匹配**任何**以 read 开头的东西——包括某天新加的 read_and_delete。"
                        + "这就是前缀授权的真实代价：它授权的是「一类名字」，不是「一批工具」。"
                        + "能接受，是因为 * 只允许在末尾、且白名单短到能一眼看完；"
                        + "如果允许 *read* 那种写法，这个代价会立刻失控");

        // 中间带 * 的写法在这里不是通配符，而是一个永远不会匹配上的字面量——
        // 这正是我们要的：不做正则，就不会有"我没想到它会匹配上"
        Role odd = new Role("odd", Role.setOf("read*delete"), true);
        assertFalse(odd.permits("read_and_delete"));
        assertFalse(odd.permits("readXdelete"));
        assertTrue(odd.permits("read*delete"), "它只是字面量");
    }

    @Test
    @DisplayName("认不出的角色名要在配置解析时就炸，不能静默退化成某种默认权限")
    void unknownRoleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Role.of("root"));
        assertThrows(IllegalArgumentException.class, () -> Rbac.fromEnv(Map.of(
                Rbac.ENV_RBAC, "alice:超级管理员:keyA")));
        assertThrows(IllegalArgumentException.class, () -> Rbac.fromEnv(Map.of(
                Rbac.ENV_RBAC, "alice:admin")));
        assertThrows(IllegalArgumentException.class, () -> Rbac.fromEnv(Map.of(
                Rbac.ENV_RBAC, "alice:admin:")));
    }

    // ------------------------------------------------------------------ 身份

    @Test
    @DisplayName("key → 身份；认不出就是不认（不退回 anonymous）")
    void identify() {
        Rbac rbac = configured();
        assertEquals("alice", rbac.identify("keyA").orElseThrow().id());
        assertEquals("bob", rbac.identify("keyB").orElseThrow().id());
        assertTrue(rbac.identify("wrong").isEmpty());
        assertTrue(rbac.identify("").isEmpty());
        assertTrue(rbac.identify(null).isEmpty());
        assertTrue(rbac.identify("key").isEmpty(), "前缀相同的短 key 不算匹配");
    }

    @Test
    @DisplayName("没配 RBAC 时退化成隐含 admin——升级不该把现有部署打挂")
    void disabledFallsBack() {
        Rbac off = Rbac.disabled();
        assertFalse(off.enabled());
        assertEquals("anonymous", off.identify("anything").orElseThrow().id());
        assertTrue(off.canApprove("any-session"));
        assertEquals(ToolPolicy.Decision.allow().allowed(),
                off.policy().check("s1", SHELL, "{}").allowed());
    }

    // ------------------------------------------------------------------ 策略

    @Test
    @DisplayName("策略按角色放行与拒绝，并且**拒绝时给出可读的原因**")
    void policyByRole() {
        Rbac rbac = configured();
        ToolPolicy policy = rbac.policy();

        rbac.bind("s-admin", rbac.identify("keyA").orElseThrow());
        rbac.bind("s-viewer", rbac.identify("keyB").orElseThrow());
        rbac.bind("s-runner", rbac.identify("keyD").orElseThrow());

        assertTrue(policy.check("s-admin", SHELL, "{}").allowed());
        assertTrue(policy.check("s-admin", ADD, "{}").allowed());

        assertTrue(policy.check("s-viewer", ADD, "{}").allowed());
        var denied = policy.check("s-viewer", SHELL, "{\"command\":\"ls\"}");
        assertFalse(denied.allowed());
        assertEquals("NOT_PERMITTED", denied.code());
        assertTrue(denied.reason().contains("viewer"), denied.reason());
        assertTrue(denied.reason().contains("shell"), "原因里要说清是哪个工具被拒了");

        assertTrue(policy.check("s-runner", SHELL, "{}").allowed());
        assertTrue(policy.check("s-runner", READ_FILE, "{}").allowed());
    }

    @Test
    @DisplayName("失败时关门：没有绑定身份的会话，一个工具都不许用")
    void failsClosedWithoutPrincipal() {
        Rbac rbac = configured();
        ToolPolicy policy = rbac.policy();

        var decision = policy.check("s-unknown", ADD, "{}");
        assertFalse(decision.allowed(),
                "漏登记的后果如果是「放行」，它就成了一条绕过授权的旁路");
        assertEquals("NO_PRINCIPAL", decision.code());
        assertTrue(decision.reason().contains("principal"), decision.reason());
    }

    @Test
    @DisplayName("与危险命令检查组合：两边都过才算过")
    void composesWithDangerousCommandPolicy() {
        Rbac rbac = configured();
        rbac.bind("s-admin", rbac.identify("keyA").orElseThrow());
        ToolPolicy combined = DefaultToolPolicy.defaults().and(rbac.policy());

        // admin 有全权工具，但命令本身危险 → 仍然被拦
        var byCommand = combined.check("s-admin", SHELL, "{\"command\":\"rm -rf /\"}");
        assertFalse(byCommand.allowed(), "RBAC 放行不等于危险命令被放行");
        assertEquals("DESTRUCTIVE_RM", byCommand.code());

        // viewer 连问都不会问到危险命令那一层（先被 RBAC 拒）
        var byRole = combined.check("s-viewer-unbound", SHELL, "{\"command\":\"echo hi\"}");
        assertFalse(byRole.allowed());
        assertEquals("NO_PRINCIPAL", byRole.code());

        assertTrue(combined.check("s-admin", ADD, "{}").allowed());
        assertEquals(ToolPolicy.Decision.allow().allowed(), ToolPolicy.allowAll().and(rbac.policy())
                .check("s-admin", ADD, "{}").allowed());
    }

    // -------------------------------------------------------------- 批准权

    @Test
    @DisplayName("「能干活」与「能批准」是两个权限：operator 能跑 shell，但不能批别人的请求")
    void approvalIsSeparateFromExecution() {
        Rbac rbac = configured();
        rbac.bind("s-carol", rbac.identify("keyC").orElseThrow());
        rbac.bind("s-alice", rbac.identify("keyA").orElseThrow());
        rbac.bind("s-dave", rbac.identify("keyD").orElseThrow());

        assertTrue(rbac.canApprove("s-alice"), "admin 可以批");
        assertFalse(rbac.canApprove("s-carol"),
                "把「能跑命令」和「能替别人做安全决定」绑在一起，结果是"
                        + "「想给某人跑命令的权限，就顺手给了他批所有请求的权限」");
        assertFalse(rbac.canApprove("s-dave"));
        assertFalse(rbac.canApprove("s-unknown"), "没有身份就不能批");
    }

    @Test
    @DisplayName("/whoami 的口径：把「这个会话到底能用哪些工具」摊开说清")
    void explainIsConcrete() {
        Rbac rbac = configured();
        rbac.bind("s-bob", rbac.identify("keyB").orElseThrow());
        var view = rbac.explain("s-bob", List.of(SHELL, ADD, READ_FILE));

        assertEquals("bob", view.get("principal"));
        assertEquals("viewer", view.get("role"));
        assertEquals(Boolean.FALSE, view.get("canApprove"));
        assertEquals(List.of("add", "read_file"), view.get("allowedTools"));
        assertEquals(List.of("shell"), view.get("deniedTools"));

        // 「谁在问」与「这个会话绑给谁」必须分开说：只看后者时，一个新会话会显示一片 null，
        // 而那很容易被读成"RBAC 没生效"
        var asking = rbac.explain("s-fresh", List.of(ADD), rbac.identify("keyA").orElseThrow());
        assertEquals("alice", asking.get("caller"));
        assertEquals("admin", asking.get("callerRole"));
        assertEquals(null, asking.get("principal"), "会话还没被建立，所以没有绑定身份");

        var unknown = rbac.explain("s-nobody", List.of(ADD));
        assertEquals(List.of(), unknown.get("allowedTools"));
        assertEquals(List.of("add"), unknown.get("deniedTools"));
        assertTrue(String.valueOf(unknown.get("note")).contains("失败时关门"));
    }

    // ------------------------------------------------------------------ 密钥

    @Test
    @DisplayName("密钥脱敏：出现在日志/审计/错误里的密钥一律变成 ***")
    void secretsAreRedacted() {
        Secrets secrets = Secrets.fromEnv(Map.of(
                "APLAT_API_KEY", "sk-live-abcdef123456",
                "APLAT_DB_PASSWORD", "p@ssw0rd-long",
                "MY_TOKEN", "tok-0123456789",
                Secrets.ENV_NAMES, "MY_TOKEN"));

        assertTrue(secrets.isSecret("MY_TOKEN"), "额外名单要生效");
        assertTrue(secrets.isSecret("APLAT_API_KEY"), "默认名单要生效——忘了配那条也是个静默失败");

        String line = "curl -H 'Authorization: Bearer sk-live-abcdef123456' "
                + "jdbc:mysql://root:p@ssw0rd-long@127.0.0.1/db?id=tok-0123456789";
        String safe = secrets.redact(line);
        assertFalse(safe.contains("sk-live-abcdef123456"), safe);
        assertFalse(safe.contains("p@ssw0rd-long"), safe);
        assertFalse(safe.contains("tok-0123456789"), safe);
        assertEquals(3, safe.split("\\*\\*\\*", -1).length - 1, "三处都该被替换: " + safe);

        // 脱敏后的长度不保留（同长度会泄露密钥长度，而那个信息对爆破有用）
        assertFalse(safe.contains("*".repeat("sk-live-abcdef123456".length())));
        assertEquals("", secrets.redact(""));
        assertEquals(null, secrets.redact(null));
    }

    @Test
    @DisplayName("太短的值不参与替换：否则正常日志会被打成筛子，于是没人再敢看日志")
    void shortValuesAreLeftAlone() {
        Secrets secrets = Secrets.fromEnv(Map.of("APLAT_API_KEY", "on"));
        assertEquals("the service is on", secrets.redact("the service is on"));

        // 长度刚好 6 的值会参与替换：这是刻意的下限，再短就会误伤正常文本
        Secrets len6 = Secrets.fromEnv(Map.of("APLAT_API_KEY", "abc123"));
        assertEquals("x *** y", len6.redact("x abc123 y"));
    }

    @Test
    @DisplayName("describe() 只报名字不报值——横幅本身就是最常泄露的地方")
    void describeNeverLeaksValues() {
        Secrets secrets = Secrets.fromEnv(Map.of("APLAT_LLM_API_KEY", "sk-secret-value-123456"));
        String text = secrets.describe();
        assertTrue(text.contains("APLAT_LLM_API_KEY"));
        assertFalse(text.contains("sk-secret-value-123456"), text);
    }
}
