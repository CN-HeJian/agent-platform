package com.aplat.sandbox;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令拦截（普通模块，不是能力缝）。
 *
 * <p>定位很重要：这是**纵深防御的最外一层**，不是唯一防线。真正的隔离靠沙箱
 * （容器/网络/资源限制），这里只是把明显的破坏性意图挡在模型与沙箱之间，
 * 并给出可归因的拒绝原因。
 *
 * <p>刻意采用"黑名单 + 拒绝理由"而不是"白名单"：白名单在真实任务里误杀率太高，
 * 会把 Agent 变成只能跑 echo 的玩具。
 */
public final class CommandPolicy {

    private static final List<Rule> RULES = List.of(
            new Rule("DESTRUCTIVE_RM", Pattern.compile("\\brm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+(/|~|\\$HOME|\\*)")),
            new Rule("DISK_WRITE", Pattern.compile("\\bdd\\s+[^\\n]*of=/dev/")),
            new Rule("FILESYSTEM_FORMAT", Pattern.compile("\\bmkfs(\\.\\w+)?\\b")),
            new Rule("FORK_BOMB", Pattern.compile(":\\(\\)\\s*\\{")),
            new Rule("PIPE_TO_SHELL", Pattern.compile("\\b(curl|wget)\\b[^\\n]*\\|\\s*(sh|bash|zsh)\\b")),
            new Rule("HOST_CONTROL", Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b")),
            new Rule("PERMISSION_ESCALATION", Pattern.compile("\\b(chmod\\s+777\\s+/|chown\\s+-R\\s+[^\\n]*\\s+/)")),
            new Rule("CREDENTIAL_EXFIL", Pattern.compile("(\\.ssh/id_rsa|\\.aws/credentials|/etc/shadow)")));

    private CommandPolicy() {
    }

    public record Decision(boolean allowed, String rule, String reason) {
        public static Decision allow() {
            return new Decision(true, null, null);
        }
    }

    private record Rule(String code, Pattern pattern) {
    }

    /** 判定一条命令/脚本是否放行。 */
    public static Decision check(String command) {
        if (command == null || command.isBlank()) {
            return Decision.allow();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(command).find()) {
                return new Decision(false, rule.code(),
                        "command matches blocked pattern " + rule.code()
                                + "; run it manually outside the agent if it is really intended");
            }
        }
        return Decision.allow();
    }
}
