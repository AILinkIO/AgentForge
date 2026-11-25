package io.ailink.agentforge;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.ailink.agentforge.cli.agent.AgentCommand;
import io.ailink.agentforge.cli.agent.TuiCommand;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;

import java.util.Arrays;

@SpringBootApplication
public class AgentForgeApplication {

    public static void main(String[] args) {
        // agent 子命令绕开 Spring：它用 agent-core 自己的模型管理，不能被"Spring AI 自动配置要 key"拦在门外
        if (args.length > 0 && args[0].equals("agent")) {
            quietLogging();
            int code = new CommandLine(new AgentCommand()).execute(Arrays.copyOfRange(args, 1, args.length));
            System.exit(code);
        }
        // 不带子命令（或 tui、或直接给开关）= 全屏工作台；同样绕开 Spring
        if (isTui(args)) {
            quietLogging();
            String[] rest = args.length > 0 && args[0].equals("tui") ? Arrays.copyOfRange(args, 1, args.length) : args;
            int code = new CommandLine(new TuiCommand()).execute(rest);
            System.out.flush();
            System.err.flush();
            // JLine 的输入线程阻塞在 read 上（终端已恢复、库已关）：直接 halt，别等它
            Runtime.getRuntime().halt(code);
        }
        SpringApplication.run(AgentForgeApplication.class, args);
    }

    /** 走全屏工作台吗：没参数、{@code tui}、或者第一个参数就是开关（{@code --spring.*} 除外，那是给 Spring 的）。 */
    static boolean isTui(String[] args) {
        if (args.length == 0) return true;
        String a = args[0];
        return a.equals("tui") || a.startsWith("-") && !a.startsWith("--spring") && !a.equals("--version");
    }

    /** 没有 Spring 的 logging 配置时 logback 缺省是 DEBUG 打到 stdout，会把 REPL 冲乱：只留 ERROR（要说的话 REPL 自己说）。 */
    static void quietLogging() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.ERROR);
        }
    }
}
