package com.aplat.seam;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 持久化缝。阶段一用 MySQL；开发/单测用内存实现顶替，保证"起服务不用先起数据库"。
 *
 * <p>注意它**不是**会话日志本身：日志是"追加 + 投影"的语义，Store 只管落盘与幂等。
 *
 * <h2>为什么有四类"存"的方式，而不是一个通用的 write()</h2>
 *
 * <p>它们解决的是**语义不同**的问题，硬合并会丢掉关键保证：
 *
 * <ol>
 *   <li>{@link #append} —— <b>只追加、序号严格递增</b>。事件溯源的根基，
 *       也是回放与断线续传（{@code Last-Event-ID}）的依据。</li>
 *   <li>{@link #saveSnapshot} —— <b>每键只留最新一份</b>。崩溃恢复只要"最近检查点"，
 *       留历史版本是纯浪费。</li>
 *   <li>{@link #markIfAbsent} —— <b>首次写入为真，且这个判断必须原子</b>。
 *       幂等的全部含义就是这份原子性；换成"先查再写"就退化成有竞态的普通读写。</li>
 *   <li>{@link #put} / {@link #get} / {@link #all} —— <b>带命名空间的普通键值</b>。
 *       任务记录、调度计划、工作区、配置版本都是这个形状：一条主键、一份 JSON、可枚举。
 *       给每一项都在能力缝上加一个方法，会让缝随功能数量线性膨胀（每加一个功能就要改接口、
 *       所有实现跟着改），而它们的存储需求其实是同一件事。</li>
 * </ol>
 *
 * <p>所以原则是：<b>语义不同就分开，语义相同就合并</b>。前三类各有不可替代的保证，
 * 第四类是"其余一切"的容器——命名空间隔离，值为 JSON 字符串。
 */
public interface Store extends Seam {

    /** 落盘一条事件并返回分配的 seq。 */
    long append(SessionEvent event);

    List<SessionEvent> events(String sessionId, long afterSeq);

    /**
     * 出现过事件的会话 id（排序）。
     *
     * <p>为什么这个方法必须存在：指标、追踪、运营台都要回答"现在有哪些会话"，
     * 而在它之前只能靠 {@code /sessions/{id}/events} 一个个问——调用方得先知道 id，
     * 于是"全量统计"这件事根本无从下手。**一个只能按已知键查询的存储，做不出运维面。**
     */
    List<String> sessionIds();

    void saveSnapshot(Snapshot snapshot);

    Optional<Snapshot> latestSnapshot(String sessionId);

    /** 幂等：首次见到返回 true，重复返回 false（执行前查、执行后标）。 */
    boolean markIfAbsent(String idempotencyKey, String ref);

    Optional<String> idempotentRef(String idempotencyKey);

    /**
     * 回填幂等键的引用（执行**之后**写结果）。
     *
     * <p>补这个方法，是因为原先的接口与它自己的文档不一致：注释写着"执行前查、执行后标"，
     * 但只有 {@link #markIfAbsent}（首次即定稿）——那个"标"根本没法落地。
     * 后果很具体：**"崩在副作用之后、检查点之前"这种情形无法被识别**，只能重跑一次副作用。
     *
     * <p>不改变 {@code markIfAbsent} 的语义：那一步仍然只认第一次；
     * 这里只是允许把"进行中"改成"已完成，结果是这个"。
     */
    void putIdempotentRef(String idempotencyKey, String ref);

    // -------------------------------------------------- 命名空间键值（其余一切）

    /** 写一条带命名空间的记录（值为 JSON 字符串）。同键覆盖。 */
    void put(String namespace, String key, String json);

    Optional<String> get(String namespace, String key);

    /** 枚举某个命名空间的全部记录（调度扫描、任务中心、配置列表都用它）。 */
    Map<String, String> all(String namespace);

    void remove(String namespace, String key);

    record Snapshot(String sessionId, String stepId, String stateJson, java.time.Instant ts) {
    }
}
