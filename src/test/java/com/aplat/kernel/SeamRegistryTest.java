package com.aplat.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.LlmAdapter;
import com.aplat.seam.LlmChunk;
import com.aplat.seam.LlmRequest;
import com.aplat.seam.Sandbox;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 服务台契约：绑定、取用、单点替换——薄内核的全部机制就这些。 */
class SeamRegistryTest {

    static class ImplA implements LlmAdapter {
        @Override
        public String id() {
            return "llm.a";
        }

        @Override
        public void stream(LlmRequest request, Consumer<LlmChunk> sink) {
            sink.accept(new LlmChunk.Done("stop"));
        }
    }

    static class ImplB implements LlmAdapter {
        @Override
        public String id() {
            return "llm.b";
        }

        @Override
        public void stream(LlmRequest request, Consumer<LlmChunk> sink) {
            sink.accept(new LlmChunk.Done("stop"));
        }
    }

    @Test
    @DisplayName("绑定后可取用，且取到的是同一实例")
    void bindAndGet() {
        SeamRegistry r = new SeamRegistry();
        ImplA a = new ImplA();
        r.bind(LlmAdapter.class, a);
        assertSame(a, r.get(LlmAdapter.class));
        assertTrue(r.isBound(LlmAdapter.class));
    }

    @Test
    @DisplayName("未绑定的能力缝取用即失败，且异常里带上是哪条缝")
    void missingSeamFailsFast() {
        SeamRegistry r = new SeamRegistry();
        MissingSeamException ex = assertThrows(MissingSeamException.class, () -> r.get(Sandbox.class));
        assertEquals(Sandbox.class, ex.seam());
        assertTrue(ex.getMessage().contains("Sandbox"));
    }

    @Test
    @DisplayName("optional 用于可降级依赖：缺失时返回 empty 而不是抛异常")
    void optionalDegradesGracefully() {
        SeamRegistry r = new SeamRegistry();
        assertEquals(Optional.empty(), r.optional(Sandbox.class));
        r.bind(Sandbox.class, new Sandbox() {
            @Override
            public String id() {
                return "sandbox.fake";
            }

            @Override
            public com.aplat.seam.ExecResult exec(com.aplat.seam.ExecRequest request) {
                return com.aplat.seam.ExecResult.of(0, "", "", java.time.Duration.ZERO);
            }

            @Override
            public boolean available() {
                return true;
            }
        });
        assertTrue(r.optional(Sandbox.class).isPresent());
    }

    @Test
    @DisplayName("重复绑定同一接口直接失败，避免装配期蒙混")
    void duplicateBindRejected() {
        SeamRegistry r = new SeamRegistry();
        r.bind(LlmAdapter.class, new ImplA());
        assertThrows(IllegalStateException.class, () -> r.bind(LlmAdapter.class, new ImplB()));
    }

    @Test
    @DisplayName("实现类型不匹配在装配期就被挡住")
    void typeMismatchRejected() {
        SeamRegistry r = new SeamRegistry();
        assertThrows(IllegalArgumentException.class, () -> bindRaw(r, LlmAdapter.class, new Object()));
    }

    /** 故意绕过泛型：模拟"绑定了一个不实现该接口的类"这种装配错误。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindRaw(SeamRegistry r, Class seam, Object impl) {
        r.bind(seam, impl);
    }

    @Test
    @DisplayName("能力缝必须是接口：拿具体类当缝直接失败")
    void seamMustBeInterface() {
        SeamRegistry r = new SeamRegistry();
        assertThrows(IllegalArgumentException.class, () -> bindRaw(r, ImplA.class, new ImplA()));
    }

    @Test
    @DisplayName("单点替换：消费方拿到的实现变了，但引用它的代码一行没改")
    void replaceSwapsImplementation() {
        SeamRegistry r = new SeamRegistry();
        r.bind(LlmAdapter.class, new ImplA());
        assertEquals("llm.a", r.get(LlmAdapter.class).id());

        r.replace(LlmAdapter.class, new ImplB());
        assertEquals("llm.b", r.get(LlmAdapter.class).id());
    }

    @Test
    @DisplayName("装配清单可枚举，排查时能看到每条缝背后是谁")
    void describeListsBindings() {
        SeamRegistry r = new SeamRegistry();
        r.bind(LlmAdapter.class, new ImplA());
        assertEquals("ImplA", r.describe().get("LlmAdapter"));
        assertFalse(r.boundSeams().isEmpty());
    }
}
