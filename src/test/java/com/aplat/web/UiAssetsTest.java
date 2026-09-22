package com.aplat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 静态托管的安全与回退行为。
 *
 * <p>这段代码是**唯一直接按用户输入拼文件路径**的地方，所以路径穿越必须单独钉住——
 * 只靠"请求一次试试"是不够的。
 */
class UiAssetsTest {

    private static UiAssets fixture(Path root) throws IOException {
        Files.writeString(root.resolve("index.html"), "<html>spa</html>");
        Files.writeString(root.resolve("app.js"), "console.log(1)");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets").resolve("index-abc.js"), "x");
        Files.createDirectories(root.resolve("..secret"));
        Files.writeString(root.resolve("..secret").resolve("in-root.txt"), "not a leak");
        return new UiAssets(root);
    }

    @Test
    @DisplayName("正常解析：根路径给 index.html，带扩展名给文件本身")
    void resolvesFiles(@TempDir Path root) throws IOException {
        UiAssets ui = fixture(root);

        assertEquals("index.html", ui.resolve("/ui/").getFileName().toString());
        assertEquals("index.html", ui.resolve("/ui").getFileName().toString());
        assertEquals("app.js", ui.resolve("/ui/app.js").getFileName().toString());
        assertEquals("index-abc.js", ui.resolve("/ui/assets/index-abc.js").getFileName().toString());
    }

    @Test
    @DisplayName("SPA 回退：无扩展名的路径给 index.html（否则前端路由一刷新就 404）")
    void spaFallback(@TempDir Path root) throws IOException {
        UiAssets ui = fixture(root);

        Path resolved = ui.resolve("/ui/chat/123");

        assertNotNull(resolved);
        assertEquals("index.html", resolved.getFileName().toString());
    }

    @Test
    @DisplayName("带扩展名但文件不存在 → 404，不回退（否则会把缺失资源悄悄变成 HTML）")
    void missingAssetWithExtensionIsNotFound(@TempDir Path root) throws IOException {
        UiAssets ui = fixture(root);

        assertNull(ui.resolve("/ui/missing.js"));
        assertNull(ui.resolve("/ui/assets/index-gone.js"));
    }

    @Test
    @DisplayName("路径穿越被挡住：.. 与编码后的 %2e%2e 都必须 404")
    void pathTraversalIsBlocked(@TempDir Path root) throws IOException {
        UiAssets ui = fixture(root);

        assertNull(ui.resolve("/ui/../../etc/passwd"), "裸 .. 必须被 normalize + startsWith 挡住");
        assertNull(ui.resolve("/ui/%2e%2e/%2e%2e/etc/passwd"), "URL 编码的 .. 要先解码再挡");
        assertNull(ui.resolve("/ui/../pom.xml"));
    }

    @Test
    @DisplayName("目录名以 .. 开头不算穿越（别把正常目录误伤）")
    void dotDotPrefixedDirectoryInsideRootIsFine(@TempDir Path root) throws IOException {
        UiAssets ui = fixture(root);

        Path resolved = ui.resolve("/ui/..secret/in-root.txt");

        assertNotNull(resolved, "根目录内的 ..secret/ 是合法目录，不该被当成穿越");
        assertEquals("in-root.txt", resolved.getFileName().toString());
    }

    @Test
    @DisplayName("未构建时 available() 为假，并给出一条能照做的提示")
    void notBuiltIsReportedHelpfully(@TempDir Path root) {
        UiAssets ui = new UiAssets(root.resolve("nope"));

        assertFalse(ui.available());
        assertNull(ui.resolve("/ui/"));
        String hint = ui.hintIfMissing().orElseThrow();
        assertTrue(hint.contains("npm run build"), "提示里要给出可执行的下一步: " + hint);
    }

    @Test
    @DisplayName("构建产物就位时不产生告警")
    void builtHasNoHint(@TempDir Path root) throws IOException {
        UiAssets ui = fixture(root);

        assertTrue(ui.available());
        assertTrue(ui.hintIfMissing().isEmpty());
    }

    @Test
    @DisplayName("MIME 类型要发对：js/css 发错会让浏览器直接拒绝执行")
    void contentTypeMapping(@TempDir Path root) {
        assertEquals("text/javascript; charset=utf-8", UiAssets.contentTypeOf(Path.of("a.js")));
        assertEquals("text/javascript; charset=utf-8", UiAssets.contentTypeOf(Path.of("a.mjs")));
        assertEquals("text/css; charset=utf-8", UiAssets.contentTypeOf(Path.of("a.css")));
        assertEquals("text/html; charset=utf-8", UiAssets.contentTypeOf(Path.of("index.html")));
        assertEquals("image/svg+xml", UiAssets.contentTypeOf(Path.of("logo.svg")));
        assertEquals("font/woff2", UiAssets.contentTypeOf(Path.of("f.woff2")));
        assertEquals("application/octet-stream", UiAssets.contentTypeOf(Path.of("weird.bin")));
        assertEquals("application/octet-stream", UiAssets.contentTypeOf(Path.of("noext")));
    }

    @Test
    @DisplayName("环境变量可改目录：APLAT_UI_DIR")
    void dirFromEnv(@TempDir Path root) {
        UiAssets ui = UiAssets.fromEnv(Map.of(UiAssets.ENV_DIR, root.toString()));

        assertEquals(root.toAbsolutePath().normalize(), ui.root());
        assertEquals(Path.of("ui/dist").toAbsolutePath().normalize(), UiAssets.fromEnv(Map.of()).root());
    }
}
