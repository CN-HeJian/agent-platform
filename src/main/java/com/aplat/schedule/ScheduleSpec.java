package com.aplat.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一条调度记录（U22）。落到 {@code Store} 的 {@code schedule} 命名空间里。
 *
 * <h2>{@code nextRunAt} 是"存下来的"而不是"算出来的"</h2>
 *
 * <p>它落在库里而不是每次读的时候现算——因为现算必然要做"上次到底跑了没有"这类推断，
 * 而推断在崩溃与重启面前不可靠。存下来之后，调度器的每次决策都只是比较两个数字。
 *
 * <h2>为什么 {@code preApprovedTools} 是**一个个工具名**</h2>
 *
 * <p>这是 HITL 与调度交叉处唯一需要新设计的地方：定时任务在凌晨两点跑，
 * 而"先问人"在那个时刻等价于"等到超时"。两条出路——提前授权、或到点没人就跳过——
 * 这里都给了：{@code preApprovedTools} 是前者，不填就是后者（走正常的 HITL，没人应答就走超时路径，
 * 模型会被告知"人不在"，它会继续做不需要批准的部分）。
 *
 * <p>但**绝不能**让"提前授权"变成一个全局开关。所以：它是逐条调度、逐个工具名填的，
 * 而且在创建时会检查这些工具是否属于"执行类"（{@code ToolSpec.commandField()} 非空）——
 * 是的话装配期会喊出来：你正在提前批准一个会执行命令的工具。这句警告不是形式主义，
 * 它把"半夜无人确认就执行 shell"这件事从隐含假设变成了显式选择。
 *
 * @param preApprovedTools 到点执行时**免确认**的工具名；空 = 全部照常走 HITL
 * @param lastOutcome      上一次执行的结果摘要；排查"这条调度为什么一直没产出"时看它
 */
public record ScheduleSpec(
        String scheduleId,
        String goal,
        String sessionId,
        Trigger trigger,
        boolean enabled,
        List<String> preApprovedTools,
        long nextRunAt,
        long lastRunAt,
        String lastTaskId,
        String lastOutcome,
        int runCount,
        long createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ScheduleSpec {
        if (scheduleId == null || scheduleId.isBlank()) {
            throw new IllegalArgumentException("scheduleId must not be blank");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("a schedule with no goal will run and do nothing");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("trigger must not be null");
        }
        preApprovedTools = preApprovedTools == null ? List.of() : List.copyOf(preApprovedTools);
        lastOutcome = lastOutcome == null ? "" : lastOutcome;
    }

    /** 新建一条调度：由 {@code now} 与触发器算出首次触发时刻。 */
    public static ScheduleSpec create(String scheduleId, String goal, String sessionId,
                                      Trigger trigger, List<String> preApprovedTools, long now) {
        long first = trigger.nextAfter(now).orElseThrow(() -> new IllegalArgumentException(
                "this trigger has no future run: " + trigger.describe()
                        + "（一次性触发器给的时刻必须晚于现在）"));
        return new ScheduleSpec(scheduleId, goal, sessionId, trigger, true, preApprovedTools,
                first, 0, null, "", 0, now);
    }

    public ScheduleSpec withNextRun(long nextRunAt, boolean enabled) {
        return new ScheduleSpec(scheduleId, goal, sessionId, trigger, enabled, preApprovedTools,
                nextRunAt, lastRunAt, lastTaskId, lastOutcome, runCount, createdAt);
    }

    /** 记下一次触发（在**开跑之前**调用：先把调度状态改掉，再干活）。 */
    public ScheduleSpec fired(String taskId, long at, boolean stillEnabled, long nextRunAt) {
        return new ScheduleSpec(scheduleId, goal, sessionId, trigger, stillEnabled, preApprovedTools,
                nextRunAt, at, taskId, "PENDING", runCount + 1, createdAt);
    }

    public ScheduleSpec finished(String outcome, long at) {
        return new ScheduleSpec(scheduleId, goal, sessionId, trigger, enabled, preApprovedTools,
                nextRunAt, at, lastTaskId, outcome, runCount, createdAt);
    }

    // ------------------------------------------------------------------ JSON

    public String toJson() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("scheduleId", scheduleId);
        n.put("goal", goal);
        n.put("sessionId", sessionId);
        n.put("enabled", enabled);
        n.put("nextRunAt", nextRunAt);
        n.put("lastRunAt", lastRunAt);
        n.put("lastTaskId", lastTaskId == null ? "" : lastTaskId);
        n.put("lastOutcome", lastOutcome);
        n.put("runCount", runCount);
        n.put("createdAt", createdAt);

        ObjectNode t = n.putObject("trigger");
        switch (trigger) {
            case Trigger.Once o -> {
                t.put("kind", "once");
                t.put("atMillis", o.atMillis());
            }
            case Trigger.Interval i -> {
                t.put("kind", "interval");
                t.put("seconds", i.seconds());
            }
            case Trigger.Daily d -> {
                t.put("kind", "daily");
                t.put("hhmm", d.hhmm());
                t.put("zone", d.zone().getId());
            }
        }

        var pre = n.putArray("preApprovedTools");
        preApprovedTools.forEach(pre::add);
        return n.toString();
    }

    public static Optional<ScheduleSpec> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            JsonNode t = n.path("trigger");
            Trigger trigger = switch (t.path("kind").asText()) {
                case "once" -> new Trigger.Once(t.path("atMillis").asLong());
                case "interval" -> new Trigger.Interval(t.path("seconds").asLong());
                case "daily" -> new Trigger.Daily(t.path("hhmm").asText(),
                        java.time.ZoneId.of(t.path("zone").asText()));
                // 读不懂的触发器：不能猜一个默认值继续跑——那会变成"每天凌晨对账"变成了"每秒对账"。
                // 返回空让上层把它报出来，是人该看一眼的事。
                default -> null;
            };
            if (trigger == null) {
                return Optional.empty();
            }
            List<String> pre = new ArrayList<>();
            n.path("preApprovedTools").forEach(x -> pre.add(x.asText()));
            return Optional.of(new ScheduleSpec(
                    n.path("scheduleId").asText(),
                    n.path("goal").asText(),
                    n.path("sessionId").asText(),
                    trigger,
                    n.path("enabled").asBoolean(true),
                    pre,
                    n.path("nextRunAt").asLong(0),
                    n.path("lastRunAt").asLong(0),
                    n.path("lastTaskId").asText(""),
                    n.path("lastOutcome").asText(""),
                    n.path("runCount").asInt(0),
                    n.path("createdAt").asLong(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 给 {@code /schedules} 用的一行人话。 */
    public String summary() {
        return scheduleId + "  " + trigger.describe()
                + "  next=" + (nextRunAt == 0 ? "-" : Instant.ofEpochMilli(nextRunAt))
                + "  runs=" + runCount
                + (enabled ? "" : "  [停用]")
                + (preApprovedTools.isEmpty() ? "" : "  免确认=" + preApprovedTools);
    }
}
