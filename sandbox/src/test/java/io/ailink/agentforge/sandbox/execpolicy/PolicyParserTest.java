package io.ailink.agentforge.sandbox.execpolicy;

import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/execpolicy/tests/basic.rs 与 src/amend.rs 的测试。 */
class PolicyParserTest {
    private static final Function<List<String>, Decision> ALLOW_ALL = c -> Decision.ALLOW;
    private static final Function<List<String>, Decision> PROMPT_ALL = c -> Decision.PROMPT;

    private static Policy parse(String src) {
        return new PolicyParser().parse("test.rules", src).build();
    }

    private static RuleMatch prefix(List<String> p, Decision d, String justification) {
        return new RuleMatch.PrefixRuleMatch(p, d, null, justification);
    }

    @Test
    void appendAllowPrefixRuleDedupesExistingRule() throws Exception {
        Path dir = Path.of(TestSupport.tempDir());
        try {
            Path file = dir.resolve("rules").resolve("default.rules");
            Amend.appendAllowPrefixRule(file, List.of("python3"));
            Amend.appendAllowPrefixRule(file, List.of("python3"));
            assertEquals("prefix_rule(pattern=[\"python3\"], decision=\"allow\")\n", Files.readString(file));
        } finally {
            TestSupport.deleteTree(dir.toString());
        }
    }

    @Test
    void appendsRuleWithoutDuplicateNewline() throws Exception {
        Path dir = Path.of(TestSupport.tempDir());
        try {
            Path file = dir.resolve("rules").resolve("default.rules");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "prefix_rule(pattern=[\"ls\"], decision=\"allow\")");
            Amend.appendAllowPrefixRule(file, List.of("echo", "Hello, world!"));
            assertEquals("prefix_rule(pattern=[\"ls\"], decision=\"allow\")\nprefix_rule(pattern=[\"echo\", \"Hello, world!\"], decision=\"allow\")\n",
                    Files.readString(file));
        } finally {
            TestSupport.deleteTree(dir.toString());
        }
    }

    @Test
    void networkRulesCompileIntoDomainLists() {
        Policy p = parse("""
                network_rule(host = "google.com", protocol = "http", decision = "allow")
                network_rule(host = "api.github.com", protocol = "https", decision = "allow")
                network_rule(host = "blocked.example.com", protocol = "https", decision = "deny")
                network_rule(host = "prompt-only.example.com", protocol = "https", decision = "prompt")
                """);
        assertEquals(4, p.networkRules().size());
        assertEquals("https", p.networkRules().get(1).protocol());
        assertEquals(List.of(List.of("google.com", "api.github.com"), List.of("blocked.example.com")), p.compiledNetworkDomains());
    }

    @Test
    void networkRuleRejectsWildcardHosts() {
        ExecPolicyException e = assertThrows(ExecPolicyException.class,
                () -> parse("network_rule(host=\"*\", protocol=\"http\", decision=\"allow\")"));
        assertTrue(e.getMessage().contains("wildcards are not allowed"), e.getMessage());
    }

    @Test
    void basicMatch() {
        Policy p = parse("""
                prefix_rule(
                    pattern = ["git", "status"],
                )
                """);
        assertEquals(new Evaluation(Decision.ALLOW, List.of(prefix(List.of("git", "status"), Decision.ALLOW, null))),
                p.check(List.of("git", "status"), ALLOW_ALL, false));
    }

    @Test
    void justificationIsAttachedToForbiddenMatches() {
        Policy p = parse("prefix_rule(pattern = [\"rm\"], decision = \"forbidden\", justification = \"destructive command\")");
        assertEquals(new Evaluation(Decision.FORBIDDEN, List.of(prefix(List.of("rm"), Decision.FORBIDDEN, "destructive command"))),
                p.check(List.of("rm", "-rf", "/some/important/folder"), ALLOW_ALL, false));
    }

    @Test
    void justificationCanBeUsedWithAllowDecision() {
        Policy p = parse("prefix_rule(pattern = [\"ls\"], decision = \"allow\", justification = \"safe and commonly used\")");
        assertEquals(new Evaluation(Decision.ALLOW, List.of(prefix(List.of("ls"), Decision.ALLOW, "safe and commonly used"))),
                p.check(List.of("ls", "-l"), PROMPT_ALL, false));
    }

    @Test
    void justificationCannotBeEmpty() {
        ExecPolicyException e = assertThrows(ExecPolicyException.class,
                () -> parse("prefix_rule(pattern = [\"ls\"], decision = \"prompt\", justification = \"   \")"));
        assertTrue(e.getMessage().contains("justification cannot be empty"));
        assertTrue(e.getMessage().startsWith("test.rules:1:1"), e.getMessage());
    }

    @Test
    void addPrefixRuleExtendsPolicy() {
        Policy p = Policy.empty().withPrefixRule(List.of("ls", "-l"), Decision.PROMPT);
        assertEquals(List.of(new PrefixRule("ls", List.of(PatternToken.single("-l")), Decision.PROMPT, null)), p.rules().get("ls"));
        assertEquals(new Evaluation(Decision.PROMPT, List.of(prefix(List.of("ls", "-l"), Decision.PROMPT, null))),
                p.check(List.of("ls", "-l", "/some/important/folder"), ALLOW_ALL, false));
        ExecPolicyException e = assertThrows(ExecPolicyException.class, () -> Policy.empty().withPrefixRule(List.of(), Decision.ALLOW));
        assertEquals("prefix cannot be empty", e.getMessage());
    }

    @Test
    void parsesMultiplePolicyFiles() {
        Policy p = new PolicyParser().parse("first.rules", "prefix_rule(pattern = [\"git\"], decision = \"prompt\")")
                .parse("second.rules", "prefix_rule(pattern = [\"git\", \"commit\"], decision = \"forbidden\")").build();
        assertEquals(List.of(new PrefixRule("git", List.of(), Decision.PROMPT, null),
                new PrefixRule("git", List.of(PatternToken.single("commit")), Decision.FORBIDDEN, null)), p.rules().get("git"));
        assertEquals(new Evaluation(Decision.PROMPT, List.of(prefix(List.of("git"), Decision.PROMPT, null))),
                p.check(List.of("git", "status"), ALLOW_ALL, false));
        assertEquals(new Evaluation(Decision.FORBIDDEN, List.of(prefix(List.of("git"), Decision.PROMPT, null),
                prefix(List.of("git", "commit"), Decision.FORBIDDEN, null))), p.check(List.of("git", "commit", "-m", "hi"), ALLOW_ALL, false));
    }

    @Test
    void onlyFirstTokenAliasExpandsToMultipleRules() {
        Policy p = parse("prefix_rule(pattern = [[\"bash\", \"sh\"], [\"-c\", \"-l\"]])");
        PatternToken alts = new PatternToken(List.of("-c", "-l"));
        assertEquals(List.of(new PrefixRule("bash", List.of(alts), Decision.ALLOW, null)), p.rules().get("bash"));
        assertEquals(List.of(new PrefixRule("sh", List.of(alts), Decision.ALLOW, null)), p.rules().get("sh"));
        assertEquals(List.of(prefix(List.of("bash", "-c"), Decision.ALLOW, null)), p.check(List.of("bash", "-c", "echo", "hi"), ALLOW_ALL, false).matchedRules());
        assertEquals(List.of(prefix(List.of("sh", "-l"), Decision.ALLOW, null)), p.check(List.of("sh", "-l", "echo", "hi"), ALLOW_ALL, false).matchedRules());
    }

    @Test
    void tailAliasesAreNotCartesianExpanded() {
        Policy p = parse("prefix_rule(pattern = [\"npm\", [\"i\", \"install\"], [\"--legacy-peer-deps\", \"--no-save\"]])");
        assertEquals(1, p.rules().get("npm").size());
        assertEquals(List.of(prefix(List.of("npm", "i", "--legacy-peer-deps"), Decision.ALLOW, null)),
                p.check(List.of("npm", "i", "--legacy-peer-deps"), ALLOW_ALL, false).matchedRules());
        assertEquals(List.of(prefix(List.of("npm", "install", "--no-save"), Decision.ALLOW, null)),
                p.check(List.of("npm", "install", "--no-save", "leftpad"), ALLOW_ALL, false).matchedRules());
    }

    @Test
    void matchAndNotMatchExamplesAreEnforced() {
        Policy p = parse("""
                prefix_rule(
                    pattern = ["git", "status"],
                    match = [["git", "status"], "git status"],
                    not_match = [
                        ["git", "--config", "color.status=always", "status"],
                        "git --config color.status=always status",
                    ],
                )
                """);
        assertEquals(List.of(prefix(List.of("git", "status"), Decision.ALLOW, null)), p.check(List.of("git", "status"), ALLOW_ALL, false).matchedRules());
        List<String> other = List.of("git", "--config", "color.status=always", "status");
        assertEquals(List.of(new RuleMatch.HeuristicsRuleMatch(other, Decision.ALLOW)), p.check(other, ALLOW_ALL, false).matchedRules());
        // 例子不对 ⇒ 读文件就报错
        assertThrows(ExecPolicyException.class, () -> parse("prefix_rule(pattern=[\"git\", \"status\"], match=[\"git diff\"])"));
        assertThrows(ExecPolicyException.class, () -> parse("prefix_rule(pattern=[\"git\"], not_match=[\"git status\"])"));
    }

    @Test
    void strictestDecisionAcrossMultipleCommands() {
        Policy p = parse("""
                prefix_rule(pattern = ["git"], decision = "prompt")
                prefix_rule(pattern = ["git", "commit"], decision = "forbidden")
                """);
        Evaluation ev = p.checkMultiple(List.of(List.of("git", "status"), List.of("git", "commit", "-m", "hi")), ALLOW_ALL, false);
        assertEquals(Decision.FORBIDDEN, ev.decision());
        assertEquals(3, ev.matchedRules().size());
    }

    @Test
    void heuristicsMatchIsReturnedWhenNoPolicyMatches() {
        assertEquals(new Evaluation(Decision.PROMPT, List.of(new RuleMatch.HeuristicsRuleMatch(List.of("python"), Decision.PROMPT))),
                Policy.empty().check(List.of("python"), PROMPT_ALL, false));
    }

    @Test
    void hostExecutables() {
        Policy p = parse("host_executable(name = \"git\", paths = [\"/opt/homebrew/bin/git\", \"/usr/bin/git\", \"/usr/bin/git\"])");
        assertEquals(List.of("/opt/homebrew/bin/git", "/usr/bin/git"), p.hostExecutables().get("git"));
        assertTrue(assertThrows(ExecPolicyException.class, () -> parse("host_executable(name = \"git\", paths = [\"git\"])")).getMessage()
                .contains("host_executable paths must be absolute"));
        assertTrue(assertThrows(ExecPolicyException.class, () -> parse("host_executable(name = \"/usr/bin/git\", paths = [\"/usr/bin/git\"])"))
                .getMessage().contains("host_executable name must be a bare executable name"));
        assertTrue(assertThrows(ExecPolicyException.class, () -> parse("host_executable(name = \"git\", paths = [\"/usr/bin/rg\"])")).getMessage()
                .contains("must have basename `git`"));
        Policy last = new PolicyParser().parse("shared.rules", "host_executable(name = \"git\", paths = [\"/usr/bin/git\"])")
                .parse("user.rules", "host_executable(name = \"git\", paths = [\"/opt/homebrew/bin/git\"])").build();
        assertEquals(List.of("/opt/homebrew/bin/git"), last.hostExecutables().get("git"));
    }

    @Test
    void hostExecutableResolution() {
        Policy p = parse("""
                prefix_rule(pattern = ["git", "status"], decision = "prompt")
                host_executable(name = "git", paths = ["/usr/bin/git"])
                """);
        assertEquals(new Evaluation(Decision.PROMPT, List.of(new RuleMatch.PrefixRuleMatch(List.of("git", "status"), Decision.PROMPT, "/usr/bin/git", null))),
                p.check(List.of("/usr/bin/git", "status"), ALLOW_ALL, true));
        // 不在声明里的路径不回退
        assertEquals(List.of(new RuleMatch.HeuristicsRuleMatch(List.of("/opt/homebrew/bin/git", "status"), Decision.ALLOW)),
                p.check(List.of("/opt/homebrew/bin/git", "status"), ALLOW_ALL, true).matchedRules());
        // 没开回退就不回退
        assertFalse(p.check(List.of("/usr/bin/git", "status"), ALLOW_ALL, false).isMatch());
        // 没有声明 ⇒ 任意绝对路径都回退
        Policy loose = parse("prefix_rule(pattern = [\"git\", \"status\"], decision = \"prompt\")");
        assertTrue(loose.check(List.of("/opt/x/git", "status"), ALLOW_ALL, true).isMatch());
        // 显式空表 ⇒ 不回退
        Policy empty = parse("prefix_rule(pattern = [\"git\", \"status\"], decision = \"prompt\")\nhost_executable(name = \"git\", paths = [])");
        assertFalse(empty.check(List.of("/usr/bin/git", "status"), ALLOW_ALL, true).isMatch());
        // 例子按回退匹配
        parse("""
                prefix_rule(
                    pattern = ["git", "status"],
                    match = [["/usr/bin/git", "status"]],
                    not_match = [["/opt/homebrew/bin/git", "status"]],
                )
                host_executable(name = "git", paths = ["/usr/bin/git"])
                """);
    }

    @Test
    void starlarkSubsetSupportsVariablesConcatenationAndComments() {
        Policy p = parse("""
                # 注释
                GIT = ["git"]
                READS = ["status", "log"]  # 行尾注释
                prefix_rule(pattern = GIT + [READS], decision = 'allow'); prefix_rule(["git", "push"], "prompt", None, None, "推送")
                """);
        assertEquals(Decision.ALLOW, p.check(List.of("git", "log"), PROMPT_ALL, false).decision());
        assertEquals(Decision.PROMPT, p.check(List.of("git", "push", "origin"), ALLOW_ALL, false).decision());
        assertEquals("推送", ((RuleMatch.PrefixRuleMatch) p.check(List.of("git", "push"), ALLOW_ALL, false).matchedRules().getFirst()).justification());
        assertThrows(ExecPolicyException.class, () -> parse("unknown_fn(x = 1)"));
        assertThrows(ExecPolicyException.class, () -> parse("prefix_rule(pattern = [\"a\"], decision = \"maybe\")"));
        ExecPolicyException e = assertThrows(ExecPolicyException.class, () -> parse("\n\nprefix_rule(pattern = UNDEFINED)"));
        assertTrue(e.getMessage().startsWith("test.rules:3:"), e.getMessage());
    }

    @Test
    void managerLoadsDirsSkipsBadFilesAndAmends() throws Exception {
        Path dir = Path.of(TestSupport.tempDir());
        try {
            Path user = dir.resolve("user"), project = dir.resolve("project");
            Files.createDirectories(user);
            Files.createDirectories(project);
            Files.writeString(user.resolve("a.rules"), "prefix_rule(pattern=[\"git\", \"push\"], decision=\"prompt\")");
            Files.writeString(project.resolve("b.rules"), "prefix_rule(pattern=[\"make\"], decision=\"allow\")\n"
                    + "prefix_rule(pattern=[\"curl\"], decision=\"forbidden\")\nnetwork_rule(host=\"x.example\", protocol=\"https\", decision=\"allow\")");
            Files.writeString(project.resolve("broken.rules"), "prefix_rule(pattern=");
            Files.writeString(project.resolve("ignored.txt"), "prefix_rule(pattern=[\"rm\"], decision=\"forbidden\")");
            ExecPolicyManager m = new ExecPolicyManager("builtin.rules", "prefix_rule(pattern=[\"sudo\"], decision=\"prompt\")",
                    List.of(user, dir.resolve("missing")), List.of(project), user.resolve("default.rules"));
            assertEquals(List.of(user.resolve("a.rules"), project.resolve("b.rules")), m.files());
            assertEquals(2, m.warnings().size(), m.warnings().toString());
            assertTrue(m.warnings().stream().anyMatch(w -> w.contains("broken.rules")));
            assertTrue(m.warnings().stream().anyMatch(w -> w.contains("2 条 allow 规则不生效")), m.warnings().toString());
            assertEquals(Decision.PROMPT, m.current().check(List.of("sudo", "ls"), ALLOW_ALL, false).decision());
            assertFalse(m.current().check(List.of("make"), PROMPT_ALL, false).isMatch(), "项目规则只能收紧：allow 丢掉");
            assertEquals(Decision.FORBIDDEN, m.current().check(List.of("curl", "x"), ALLOW_ALL, false).decision(), "收紧的照收");
            assertEquals(List.of(List.of(), List.of()), m.current().compiledNetworkDomains());
            assertFalse(m.current().check(List.of("rm"), ALLOW_ALL, false).isMatch());

            m.appendAllowPrefixRule(List.of("echo", "hello"));
            assertTrue(m.alreadyAllowed(List.of("echo", "hello")));
            assertEquals("prefix_rule(pattern=[\"echo\", \"hello\"], decision=\"allow\")\n", Files.readString(user.resolve("default.rules")));
            m.appendNetworkRule("Example.COM:443", "https", Decision.FORBIDDEN, null);
            assertEquals(List.of(List.of(), List.of("example.com")), m.current().compiledNetworkDomains());
            m.reload();
            assertTrue(m.alreadyAllowed(List.of("echo", "hello")), "修订落盘了，重读还在");
            assertEquals(List.of(List.of(), List.of("example.com")), m.current().compiledNetworkDomains());
            assertThrows(ExecPolicyException.class, () -> m.appendAllowPrefixRule(List.of()));
        } finally {
            TestSupport.deleteTree(dir.toString());
        }
    }
}
