package com.aplat.schedule;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.OptionalLong;

/**
 * 触发规则（U22）。
 *
 * <p>只做三种，够覆盖"定时 / 周期 / 延迟"：一次性、固定间隔、每日定点。
 * **刻意不做完整 cron 表达式**：那需要一整套解析器与边界语义（`0 0 31 2 *` 到底跑不跑？），
 * 而这一版的调度要解决的是"半夜两点把日报跑出来"，不是替代系统的 crond。
 * 要 cron 就换个实现接在同一个 {@link Scheduler} 上——它只依赖这个接口。
 *
 * <h2>{@link #nextAfter} 的语义是"严格晚于"</h2>
 *
 * <p>必须严格，不能"大于等于"。否则一个每秒执行、每次耗时 300ms 的周期任务，
 * 在 {@code from} 恰好吃在整秒上时会算出"下一次就是现在"，于是同一时刻被触发两次。
 * 这个坑在调度器里表现为"偶尔跑两遍"，而且只在特定时刻出现——最难查的那类。
 */
public sealed interface Trigger {

    /**
     * 下一次触发时刻（epoch 毫秒），严格晚于 {@code fromMillis}。
     *
     * @return 空 = 不再触发（一次性任务已经过点）
     */
    OptionalLong nextAfter(long fromMillis);

    /** 给人看的描述，出现在 {@code /schedules} 里。 */
    String describe();

    /** 到点跑一次，之后不再触发。用于"延迟到某个时刻"。 */
    record Once(long atMillis) implements Trigger {
        @Override
        public OptionalLong nextAfter(long fromMillis) {
            return atMillis > fromMillis ? OptionalLong.of(atMillis) : OptionalLong.empty();
        }

        @Override
        public String describe() {
            return "once@" + Instant.ofEpochMilli(atMillis);
        }
    }

    /** 固定间隔（秒）。从"当前时刻"起算，而不是从"上次计划时刻"起算——理由见 Scheduler 的错过后策略。 */
    record Interval(long seconds) implements Trigger {
        public Interval {
            if (seconds <= 0) {
                throw new IllegalArgumentException("interval must be positive: " + seconds);
            }
        }

        @Override
        public OptionalLong nextAfter(long fromMillis) {
            return OptionalLong.of(fromMillis + seconds * 1000L);
        }

        @Override
        public String describe() {
            return "every " + seconds + "s";
        }
    }

    /**
     * 每日固定时刻（本地时区），形如 {@code "02:30"}。
     *
     * <p>用**本地时区**：运维说"每天凌晨两点"说的是墙上时钟，不是 UTC。
     * 但由此带来一个必须说明的行为：夏令时切换那天，同一个本地时刻可能对应两个瞬间
     * （秋季重复），或干脆不存在（春季跳过）。这里用 {@code atZone} 的既有约定处理——
     * 重复时取第一个、缺失时顺延——**不自己发明规则**。
     */
    record Daily(String hhmm, ZoneId zone) implements Trigger {
        public Daily {
            parseTime(hhmm);
            if (zone == null) {
                throw new IllegalArgumentException("zone must not be null");
            }
        }

        public static Daily of(String hhmm) {
            return new Daily(hhmm, ZoneId.systemDefault());
        }

        @Override
        public OptionalLong nextAfter(long fromMillis) {
            LocalTime target = parseTime(hhmm);
            ZonedDateTime cursor = Instant.ofEpochMilli(fromMillis).atZone(zone);
            for (int dayOffset = 0; dayOffset <= 2; dayOffset++) {
                ZonedDateTime candidate = cursor.toLocalDate().plusDays(dayOffset)
                        .atTime(target).atZone(zone);
                if (candidate.toInstant().toEpochMilli() > fromMillis) {
                    return OptionalLong.of(candidate.toInstant().toEpochMilli());
                }
            }
            // 走不到这里：最多两天之内必然有一个晚于 from 的同名时刻
            throw new IllegalStateException("daily trigger could not resolve next run after " + fromMillis);
        }

        @Override
        public String describe() {
            return "daily@" + hhmm + "[" + zone.getId() + "]";
        }

        private static LocalTime parseTime(String raw) {
            try {
                return LocalTime.parse(raw);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "daily time must look like \"HH:mm\", got: " + raw);
            }
        }
    }
}
