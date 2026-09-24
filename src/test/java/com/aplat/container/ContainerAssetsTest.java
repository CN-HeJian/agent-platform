package com.aplat.container;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.run.Main;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U26 验收：容器化资产 + 配置即代码。
 *
 * <h2>为什么这一版是**静态**校验，以及它为什么不是走过场</h2>
 *
 * <p>做这个的时候本机没有 Docker。所以这里没有 {@code docker build}——这一点必须写明，
 * 否则一份写着「容器化 ✅」的说明很容易被读成「验过」。**做了静态校验就说静态校验。**
 *
 * <p>但静态校验在这里抓到的是**真问题**，不是形式检查：
 *
 * <ol>
 *   <li>Dockerfile 里写的子命令必须在 {@link Main} 的白名单里。写错的那一刻就拦下，
 *       而不是等起容器看到「未知命令」。</li>
 *   <li>compose / env 文件里出现的每个 {@code APLAT_*} 变量，代码里都真的读过它。
 *       拼错的变量是最难查的一类配置错误：表现是「配置没生效」，而没有任何报错。
 *       做法是把源码里所有 {@code ENV_*} 常量的**值**扫出来当白名单——
 *       于是常量就真的是唯一真相来源，连编排文件与文档也得跟它对齐。</li>
 *   <li>镜像里不该有的东西（构建产物、.git、node_modules）在 .dockerignore 里。</li>
 * </ol>
 */
class ContainerAssetsTest {

    /** 模块根目录：surefire 的工作目录就是模块目录。 */
    private static final Path MODULE = Path.of("").toAbsolutePath();

    /** 非本项目定义的变量：基础镜像 / 编排层自己的。 */
    private static final Set<String> FOREIGN = Set.of(
            "MYSQL_ROOT_PASSWORD", "MYSQL_DATABASE", "PATH", "JAVA_HOME", "HOME", "TZ");

    private static String read(String relative) throws IOException {
        Path p = MODULE.resolve(relative);
        assertTrue(Files.exists(p), "缺少文件: " + relative);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- 环境变量白名单

    /**
     * 把源码里 {@code static final String ENV_XXX = "APLAT_YYY"} 的值全部扫出来。
     *
     * <p>扫源码而不是维护一份手写清单：手写清单一定会过期，而过期的那一刻，这个测试
     * 就从保护变成了阻碍（有人会去改清单而不是改对代码）。
     */
    private static Set<String> knownEnvNames() throws IOException {
        Pattern declaration = Pattern.compile("String\\s+ENV_[A-Z0-9_]+\\s*=\\s*\"([^\"]+)\"");
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MODULE.resolve("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = declaration.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    /** 从形如 {@code KEY=value} / {@code KEY: value} 的文本里取出所有匹配到的变量名。 */
    private static Set<String> keysIn(String text, Pattern keyPattern) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = keyPattern.matcher(text);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static void assertAllKnown(String where, Set<String> used, Set<String> known) {
        Set<String> unknown = new TreeSet<>(used);
        unknown.removeAll(known);
        assertTrue(unknown.isEmpty(),
                where + " 里这些变量代码里没读过（拼错了？）: " + unknown + "，已知的: " + known);
    }

    // ------------------------------------------------------------------ Dockerfile

    @Test
    @DisplayName("Dockerfile：健康检查打 /health、非 root、两阶段——三件都不能少")
    void dockerfileHasTheThreeThings() throws IOException {
        String docker = read("Dockerfile");

        assertTrue(docker.contains("HEALTHCHECK"), "没有健康检查，编排层只能靠端口通不通来判断");
        assertTrue(docker.contains("/health"),
                "健康检查要打 /health（它含 store 与 hitl 的真实状态），而不是只探端口。"
                        + "只探端口的话，服务起来了但连不上库，在编排层看来仍是健康的，于是流量照发");
        assertTrue(docker.contains("USER aplat"), "必须以非 root 运行：这个服务能执行 shell");
        assertFalse(docker.contains("USER root"), "显式切回 root 更糟——那是一条被写下来的例外");

        assertTrue(docker.matches("(?s).*FROM\\s+\\S*jdk\\S*\\s+AS\\s+build.*"), "缺少编译阶段");
        assertTrue(docker.contains("COPY --from=build"), "缺少从编译阶段取产物那一步");
        assertTrue(docker.contains("-jar"), "运行方式应当是把 jar 直接起起来");

        int pomCopy = docker.indexOf("COPY pom.xml");
        int srcCopy = docker.indexOf("COPY src src");
        assertTrue(pomCopy > 0 && srcCopy > pomCopy,
                "要先拷 POM 跑一次依赖解析、再拷 src——否则每次改一行代码都要重下整个依赖树");
    }

    @Test
    @DisplayName("Dockerfile 里用的子命令必须在 Main 的白名单里——拼错就在构建前拦下")
    void dockerfileEntrypointUsesAKnownSubcommand() throws IOException {
        String docker = read("Dockerfile");
        Matcher m = Pattern.compile("ENTRYPOINT\\s*\\[(.*?)]", Pattern.DOTALL).matcher(docker);
        assertTrue(m.find(), "没找到 ENTRYPOINT");

        List<String> argv = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1)).results()
                .map(r -> r.group(1)).toList();
        String subcommand = argv.get(argv.size() - 1);

        assertTrue(Main.commands().contains(subcommand),
                "Dockerfile 用的是 '" + subcommand + "'，而 Main 认识的是 " + Main.commands()
                        + "。容器里没有 Maven，起不来的表现是「未知命令」，"
                        + "而那时人已经在排查容器了");
    }

    @Test
    @DisplayName("Dockerfile 里出现的环境变量也必须在代码里被读过")
    void dockerfileEnvNamesAreKnown() throws IOException {
        assertAllKnown("Dockerfile",
                keysIn(read("Dockerfile"),
                        Pattern.compile("(?m)^\\s*(?:ENV\\s+|\\$\\{)?(APLAT_[A-Z0-9_]+)")),
                knownEnvNames());
    }

    // ------------------------------------------------------------------ compose

    @Test
    @DisplayName("compose：变量都存在；端口只绑回环；等 db 真健康；审计挂持久卷")
    void composeIsConsistent() throws IOException {
        String compose = read("docker-compose.yml");

        Set<String> used = keysIn(compose, Pattern.compile("(?m)^\\s*(APLAT_[A-Z0-9_]+):"));
        used.addAll(keysIn(compose, Pattern.compile("\\$\\{(APLAT_[A-Z0-9_]+)")));
        assertAllKnown("docker-compose.yml", used, knownEnvNames());

        assertTrue(compose.contains("127.0.0.1:8787:8787"),
                "compose 必须把端口绑在 127.0.0.1 上——写成 8787:8787 等于对全网开放一个"
                        + "能执行命令的服务（与 Serve 默认只听回环是同一个理由）");
        assertTrue(compose.contains("service_healthy"),
                "depends_on 必须等 db 真的可用：应用连不上库会启动失败（这是刻意的），"
                        + "只等容器已启动会让它必然启动失败一次");
        assertTrue(compose.contains("/app/logs"), "审计要挂到卷上，否则它活不过一次容器重建");
        assertTrue(compose.contains("静态校验"),
                "compose 里要写明它只做过静态校验——否则读起来像验过");
    }

    @Test
    @DisplayName(".dockerignore 排掉构建产物、.git 与 node_modules")
    void dockerignoreExcludesTheUsualSuspects() throws IOException {
        String ignore = read(".dockerignore");
        assertTrue(ignore.contains("target/"), "构建产物不该进镜像上下文");
        assertTrue(ignore.contains(".git/"), ".git 记录的是我们怎么改的，不是怎么跑的");
        assertTrue(ignore.contains("ui/node_modules/"), "前端依赖不该进任何镜像");
        assertTrue(ignore.contains("*.jsonl") || ignore.contains("logs/"), "运行痕迹不该进镜像");
    }

    // ------------------------------------------------------------------ 配置即代码

    @Test
    @DisplayName("每个环境的 env 文件只用代码里认识的变量")
    void envProfilesUseKnownVariables() throws IOException {
        Set<String> known = knownEnvNames();
        for (String file : List.of("config/env/dev.env", "config/env/prod.env.example")) {
            Set<String> used = keysIn(read(file), Pattern.compile("(?m)^\\s*(APLAT_[A-Z0-9_]+)="));
            assertFalse(used.isEmpty(), file + " 里一个 APLAT_ 变量都没有？");
            assertAllKnown(file, used, known);
        }
    }

    @Test
    @DisplayName("本地与生产的差异只在该差的地方：鉴权与确认在生产必须开")
    void devAndProdDifferExactlyWhereTheyShould() throws IOException {
        String dev = read("config/env/dev.env");
        String prod = read("config/env/prod.env.example");

        assertTrue(prod.contains("APLAT_API_KEY="), "生产配置必须要求 API key");
        assertTrue(prod.contains("APLAT_HITL=ask"), "生产必须 ask：无人确认时宁可超时也不能默认放行");
        assertTrue(dev.contains("APLAT_HITL=allow"), "本地用 allow，否则每次调试都要点批准");
        assertFalse(dev.contains("APLAT_API_KEY="), "本地不该要求 key（只听 127.0.0.1）");

        assertFalse(prod.matches("(?s).*APLAT_(API_KEY|LLM_API_KEY|DB_PASSWORD)=[^\\s].*"),
                "prod.env.example 里出现了非空的值——它会被当成模板抄走，然后真的进了生产");
    }

    @Test
    @DisplayName("config/README 把生产与本地差在哪写成了一张表")
    void configReadmeExplainsTheDifference() throws IOException {
        String readme = read("config/README.md");
        for (String key : List.of("APLAT_DB_URL", "APLAT_API_KEY", "APLAT_HITL", "APLAT_RBAC")) {
            assertTrue(readme.contains(key), "差异表里少了 " + key);
        }
        assertTrue(readme.contains("|"), "要有一张表，而不是一段散文");
    }

    @Test
    @DisplayName("Main 的分发器是显式白名单，拼错子命令给的是明确报错")
    void launcherIsExplicit() {
        assertTrue(Main.commands().contains("serve"));
        assertTrue(Main.commands().containsAll(List.of("demo", "crash", "mcp-demo")),
                "真实可用的子命令: " + Main.commands());
    }

    @Test
    @DisplayName("白名单扫描本身要扫到足够多的变量——否则上面几条会变成永远通过的空检查")
    void knownEnvNamesScanIsNotVacuous() throws IOException {
        Set<String> known = knownEnvNames();
        assertTrue(known.size() >= 10, "只扫到 " + known.size() + " 个环境变量常量，扫描逻辑是不是坏了？");
        for (String must : List.of("APLAT_API_KEY", "APLAT_DB_URL", "APLAT_RBAC",
                "APLAT_MCP_ENDPOINTS", "APLAT_HITL")) {
            assertTrue(known.contains(must), "白名单里少了 " + must + "：" + known);
        }
        assertFalse(known.contains("MYSQL_ROOT_PASSWORD"),
                "外来的变量名不该被当成我们的，否则白名单会松到失去意义");
        assertTrue(FOREIGN.contains("MYSQL_ROOT_PASSWORD"), "外来名单别删——它是白名单的反面样本");
    }
}
