package com.aplat.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U22 验收：触发规则的边界。
 *
 * <p>调度最容易出的问题不是"没触发"，而是"触发得比预期多"——多跑一次对账、
 * 多扣一次款。所以这里的测试大多数是在钉**不会多触发**。
 */
class TriggerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static long at(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), ZONE)
                .toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("一次性：过点后不再触发（空 = 调度该停用）")
    void once() {
        Trigger t = new Trigger.Once(at("2026-09-23T10:00:00"));
        assertEquals(at("2026-09-23T10:00:00"), t.nextAfter(at("2026-09-23T09:00:00")).orElseThrow());
        assertTrue(t.nextAfter(at("2026-09-23T10:00:00")).isEmpty(),
                "恰好等于触发时刻**不算**下一次：否则同一时刻会被触发两次");
        assertTrue(t.nextAfter(at("2026-09-23T11:00:00")).isEmpty());
    }

    @Test
    @DisplayName("间隔：从 now 起算，不从「上次计划时刻」起算（这是不补跑的实现基础）")
    void interval() {
        Trigger t = new Trigger.Interval(60);
        // 停机两小时后重启：下一次是 now + 60s，不是"补上那 120 次"
        assertEquals(at("2026-09-23T12:01:00"), t.nextAfter(at("2026-09-23T12:00:00")).orElseThrow());
        assertEquals(at("2026-09-23T12:00:00") + 60_000, t.nextAfter(at("2026-09-23T12:00:00")).orElseThrow());
    }

    @Test
    @DisplayName("间隔必须是正的——0 会让 tick 每轮都判定为到期，把服务打成死循环")
    void intervalMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Trigger.Interval(0));
        assertThrows(IllegalArgumentException.class, () -> new Trigger.Interval(-5));
    }

    @Test
    @DisplayName("每日定点：今天还没到就是今天，过了就是明天")
    void daily() {
        Trigger t = new Trigger.Daily("02:30", ZONE);
        assertEquals(at("2026-09-23T02:30:00"), t.nextAfter(at("2026-09-23T01:00:00")).orElseThrow());
        assertEquals(at("2026-09-24T02:30:00"), t.nextAfter(at("2026-09-23T03:00:00")).orElseThrow(),
                "过点之后必须是明天，否则会立刻再触发一次");
        assertEquals(at("2026-09-24T02:30:00"), t.nextAfter(at("2026-09-23T02:30:00")).orElseThrow(),
                "恰好等于触发时刻同样不算下一次");
    }

    @Test
    @DisplayName("每日定点跨时区按各自的墙上时钟：同一个 HH:mm 在不同时区是不同的瞬间")
    void dailyRespectsZone() {
        long now = at("2026-09-23T09:00:00"); // 上海 09:00 = UTC 01:00
        long shanghai = new Trigger.Daily("10:00", ZONE).nextAfter(now).orElseThrow();
        long utc = new Trigger.Daily("10:00", ZoneId.of("UTC")).nextAfter(now).orElseThrow();
        assertTrue(utc > shanghai, "UTC 的 10:00 比上海的 10:00 晚 8 小时才到");

        // 上海 10:00 就是 UTC 02:00
        assertEquals(Instant.parse("2026-09-23T02:00:00Z").toEpochMilli(), shanghai);
        assertEquals(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli(), utc);
    }

    @Test
    @DisplayName("格式写错要在建的时候炸，不能等到凌晨两点才发现")
    void dailyFormatIsCheckedEagerly() {
        assertThrows(IllegalArgumentException.class, () -> new Trigger.Daily("2点半", ZONE));
        assertThrows(IllegalArgumentException.class, () -> new Trigger.Daily("25:00", ZONE));
        assertEquals(LocalTime.of(2, 30), LocalTime.parse("02:30"));
    }

    @Test
    @DisplayName("夏令时：跳到不存在的时刻时顺延，不抛异常也不静默丢一天")
    void dailyDuringDaylightSavingGap() {
        // 2026-03-29 02:00→03:00 是欧洲的春季跳变，02:30 这个本地时刻当天不存在
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Trigger t = new Trigger.Daily("02:30", berlin);
        long before = ZonedDateTime.of(LocalDate.of(2026, 3, 29), LocalTime.of(1, 0), berlin)
                .toInstant().toEpochMilli();
        long next = t.nextAfter(before).orElseThrow();
        assertTrue(next > before);
        long oneDay = 24 * 3600_000L;
        assertTrue(next - before < oneDay, "顺延到当天更晚的时刻，而不是跳过一整天");

        // 秋季重复时刻（02:30 出现两次）取第一个，不重复触发
        ZoneId berlin2 = ZoneId.of("Europe/Berlin");
        long nov = ZonedDateTime.of(LocalDate.of(2026, 10, 25), LocalTime.of(0, 0), berlin2)
                .toInstant().toEpochMilli();
        long first = new Trigger.Daily("02:30", berlin2).nextAfter(nov).orElseThrow();
        long again = new Trigger.Daily("02:30", berlin2).nextAfter(first).orElseThrow();
        assertTrue(again - first >= 23 * 3600_000L,
                "同一个本地时刻的第二次出现不该被当成新的一天再触发一次");
    }

    @Test
    @DisplayName("describe() 是人要看的那一行，不能是 toString 的默认样子")
    void describeIsHumanReadable() {
        assertTrue(new Trigger.Interval(30).describe().contains("30"));
        assertTrue(new Trigger.Daily("07:15", ZONE).describe().contains("07:15"));
        assertFalse(new Trigger.Once(0).describe().isBlank());
    }
}
