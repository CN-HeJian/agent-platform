package com.aplat.web;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 把 {@code ui/dist} 的打包产物挂到 {@code /ui/} 下（U07a 的落地方式）。
 *
 * <p>为什么由 Java 服务顺带托管，而不是让用户另外起一个 Node：
 * 这个平台现在只需要<b>一个进程、一个端口</b>。前端是编译产物（静态文件），
 * 让后端顺手发出去，比多维护一个 Node 服务省事得多。
 *
 * <p>三个必须做对的点：
 * <ol>
 *   <li><b>路径穿越防护</b>：{@code /ui/../../etc/passwd} 这类请求必须被挡住。
 *       所有路径先 normalize，再断言仍在根目录之下。</li>
 *   <li><b>SPA 回退</b>：前端路由下 {@code /ui/chat/1} 在磁盘上没有对应文件，
 *       要回退到 index.html，否则刷新页面就 404。</li>
 *   <li><b>缺产物时说人话</b>：没跑过 {@code npm run build} 时给一条明确的提示，
 *       而不是干巴巴的 404。</li>
 * </ol>
 *
 * <pre>
 *   APLAT_UI_DIR   dist 目录位置，默认 "ui/dist"（相对启动时的工作目录）
 * </pre>
 */
public final class UiAssets {

    public static final String ENV_DIR = "APLAT_UI_DIR";
    public static final String URL_PREFIX = "/ui/";

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private final Path root;

    public UiAssets(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static UiAssets fromEnv() {
        return fromEnv(System.getenv());
    }

    public static UiAssets fromEnv(Map<String, String> env) {
        String dir = env.getOrDefault(ENV_DIR, "ui/dist");
        return new UiAssets(Path.of(dir));
    }

    public Path root() {
        return root;
    }

    /** 构建产物是否就位。 */
    public boolean available() {
        return Files.isRegularFile(root.resolve("index.html"));
    }

    /** 给启动日志用的一句人话提示；已就位时返回 empty。 */
    public Optional<String> hintIfMissing() {
        if (available()) {
            return Optional.empty();
        }
        return Optional.of("前端未构建：" + root + "/index.html 不存在。"
                + "执行 `cd ui && npm install && npm run build` 后重启即可；"
                + "期间 /ui/ 会返回 404（其余接口不受影响）。");
    }

    /**
     * 解析 /ui/ 下的一个请求路径。
     *
     * @param rawPath {@code /ui/...}
     * @return 命中的文件；null = 应该 404
     */
    public Path resolve(String rawPath) {
        String relative = rawPath.startsWith(URL_PREFIX)
                ? rawPath.substring(URL_PREFIX.length())
                : rawPath.substring(Math.min(rawPath.length(), "/ui".length()));
        relative = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        if (relative.isBlank()) {
            relative = "index.html";
        }

        Path candidate = root.resolve(relative).normalize();
        // 路径穿越：normalize 之后必须仍在根目录之下
        if (!candidate.startsWith(root)) {
            return null;
        }
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        // SPA 回退：无扩展名的路径认为是前端路由，交给 index.html
        String last = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
        if (!last.contains(".")) {
            Path index = root.resolve("index.html");
            return Files.isRegularFile(index) ? index : null;
        }
        return null;
    }

    public byte[] read(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    public static String contentTypeOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
