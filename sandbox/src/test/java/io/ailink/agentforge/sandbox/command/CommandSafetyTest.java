package io.ailink.agentforge.sandbox.command;

import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.Platform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/shell-command/src/command_safety/is_dangerous_command.rs 与
 * is_safe_command.rs（942af8447b 之前的版本）的测试。
 */
class CommandSafetyTest {
    private static List<String> v(String... s) {
        return List.of(s);
    }

    @Test
    void knownSafeExamples() {
        assertTrue(CommandSafety.isSafeToCallWithExec(v("ls")));
        assertTrue(CommandSafety.isSafeToCallWithExec(v("base64")));
        assertTrue(CommandSafety.isSafeToCallWithExec(v("sed", "-n", "1,5p", "file.txt")));
        assertTrue(CommandSafety.isSafeToCallWithExec(v("nl", "-nrz", "Cargo.toml")));
        assertTrue(CommandSafety.isSafeToCallWithExec(v("find", ".", "-name", "file.txt")));
        assertEquals(Platform.isLinux(), CommandSafety.isSafeToCallWithExec(v("numfmt", "1000")));
        assertEquals(Platform.isLinux(), CommandSafety.isSafeToCallWithExec(v("tac", "Cargo.toml")));
    }

    @Test
    void gitCommandsAreNotKnownSafe() {
        for (List<String> args : List.of(v("git", "status", "--short"), v("git", "log", "-p", "-1"), v("git", "diff"), v("git", "show", "HEAD"),
                v("git", "branch"), v("git", "branch", "--show-current"), v("git", "--version"), v("/usr/bin/git", "status"),
                v("bash", "-lc", "git status"), v("zsh", "-lc", "cd nested && git status"), v("bash", "-lc", "git diff | head -20")))
            assertFalse(CommandSafety.isKnownSafe(args), args.toString());
    }

    @Test
    void cargoCheckIsNotSafe() {
        assertFalse(CommandSafety.isKnownSafe(v("cargo", "check")));
    }

    @Test
    void zshLcSafeCommandSequence() {
        assertTrue(CommandSafety.isKnownSafe(v("zsh", "-lc", "ls")));
    }

    @Test
    void unknownOrPartial() {
        assertFalse(CommandSafety.isSafeToCallWithExec(v("foo")));
        assertFalse(CommandSafety.isSafeToCallWithExec(v("git", "fetch")));
        assertFalse(CommandSafety.isSafeToCallWithExec(v("sed", "-n", "xp", "file.txt")));
        for (List<String> args : List.of(v("find", ".", "-name", "file.txt", "-exec", "rm", "{}", ";"),
                v("find", ".", "-name", "*.py", "-execdir", "python3", "{}", ";"), v("find", ".", "-name", "file.txt", "-ok", "rm", "{}", ";"),
                v("find", ".", "-name", "*.py", "-okdir", "python3", "{}", ";"), v("find", ".", "-delete", "-name", "file.txt"),
                v("find", ".", "-fls", "/etc/passwd"), v("find", ".", "-fprint", "/etc/passwd"), v("find", ".", "-fprint0", "/etc/passwd"),
                v("find", ".", "-fprintf", "/root/suid.txt", "%#m %u %p\n")))
            assertFalse(CommandSafety.isSafeToCallWithExec(args), args.toString());
    }

    @Test
    void base64OutputOptionsAreUnsafe() {
        for (List<String> args : List.of(v("base64", "-o", "out.bin"), v("base64", "--output", "out.bin"), v("base64", "--output=out.bin"),
                v("base64", "-ob64.txt")))
            assertFalse(CommandSafety.isSafeToCallWithExec(args), args.toString());
    }

    @Test
    void ripgrepRules() {
        assertTrue(CommandSafety.isSafeToCallWithExec(v("rg", "Cargo.toml", "-n")));
        for (List<String> args : List.of(v("rg", "--search-zip", "files"), v("rg", "-z", "files"), v("rg", "--pre", "pwned", "files"),
                v("rg", "--pre=pwned", "files"), v("rg", "--hostname-bin", "pwned", "files"), v("rg", "--hostname-bin=pwned", "files")))
            assertFalse(CommandSafety.isSafeToCallWithExec(args), args.toString());
    }

    @Test
    void bashLcSafeExamples() {
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "ls")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "ls -1")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "grep -R \"Cargo.toml\" -n")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "sed -n 1,5p file.txt")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "sed -n '1,5p' file.txt")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "find . -name file.txt")));
    }

    @Test
    void bashLcSafeExamplesWithOperators() {
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "grep -R \"Cargo.toml\" -n || true")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "ls && pwd")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "echo 'hi' ; ls")));
        assertTrue(CommandSafety.isKnownSafe(v("bash", "-lc", "ls | wc -l")));
    }

    @Test
    void bashLcUnsafeExamples() {
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "git", "status")), "四个参数的形式不算");
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "'git status'")));
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "find . -name file.txt -delete")));
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "ls && rm -rf /")));
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "(ls)")));
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "ls || (pwd && echo hi)")));
        assertFalse(CommandSafety.isKnownSafe(v("bash", "-lc", "ls > out.txt")));
    }

    // ── 危险 ─────────────────────────────────────────────────────────

    @Test
    void rmRfAndRmFAreDangerous() {
        assertEquals(CommandSafety.DangerousMatch.FORCED_RM, CommandSafety.dangerousMatch(v("rm", "-rf", "/")));
        assertEquals(CommandSafety.DangerousMatch.FORCED_RM, CommandSafety.dangerousMatch(v("rm", "-f", "/")));
    }

    @Test
    void forcedRmVariantsAreDangerous() {
        for (List<String> c : List.of(v("/bin/rm", "-fr", "/tmp/example"), v("rm", "-r", "-f", "/tmp/example"), v("rm", "--force", "/tmp/example"),
                v("rm", "/tmp/example", "-f"), v("sudo", "rm", "-rf", "/tmp/example"), v("env", "TARGET=/tmp/example", "rm", "-rf", "/tmp/example")))
            assertEquals(CommandSafety.DangerousMatch.FORCED_RM, CommandSafety.dangerousMatch(c), c.toString());
    }

    @Test
    void deeplyNestedCommandWrappersFailClosed() {
        for (int depth : new int[] {CommandSafety.MAX_DANGEROUS_COMMAND_WRAPPER_DEPTH, CommandSafety.MAX_DANGEROUS_COMMAND_WRAPPER_DEPTH + 1}) {
            List<String> cmd = new ArrayList<>(Collections.nCopies(depth, "env"));
            cmd.addAll(v("rm", "-rf", "/tmp/example"));
            assertEquals(depth == CommandSafety.MAX_DANGEROUS_COMMAND_WRAPPER_DEPTH ? CommandSafety.DangerousMatch.FORCED_RM
                    : CommandSafety.DangerousMatch.OTHER, CommandSafety.dangerousMatch(cmd));
        }
    }

    @Test
    void forcedRmInComplexShellSyntaxIsDangerous() {
        for (String script : List.of("printf x | rm -rf /tmp/example", "if test -d /tmp/example; then rm --force /tmp/example; fi",
                "rm -rf \"$TARGET\" >/dev/null", "for target in /tmp/a /tmp/b; do rm -r -f \"$target\"; done", "echo \"$(rm -rf /tmp/example)\"",
                "bash -c 'rm -rf /tmp/example'", "trap 'rm -rf /tmp/example' EXIT",
                "for a in '-C5a25KeRr' '--' '--json' '--bogus'; do HOME=$(mktemp -d) MDE_URL=http://127.0.0.1:1 MDE_TOKEN=x node cli/mde.cjs ls \"$a\" "
                        + ">/tmp/mde-review-out 2>/tmp/mde-review-err; code=$?; printf '%s\\t%s\\t%s\\n' \"$a\" \"$code\" "
                        + "\"$(tr '\\n' ' ' </tmp/mde-review-err)\"; rm -rf \"$HOME\"; done"))
            assertEquals(CommandSafety.DangerousMatch.FORCED_RM, CommandSafety.dangerousMatch(v("bash", "-lc", script)), script);
    }

    @Test
    void nonForcedOrNonLiteralRmIsNotDangerous() {
        for (List<String> c : List.of(v("rm", "-r", "/tmp/example"), v("rm", "--", "-f"), v("bash", "-lc", "echo 'rm -rf /tmp/example'"),
                v("bash", "-lc", "cmd=rm; $cmd -rf /tmp/example"), v("bash", "-lc", "if then rm -rf /tmp/example"),
                v("env", "TARGET=/tmp/example", "rm", "-r", "/tmp/example"), v("bash", "-lc", "trap 'echo rm -rf /tmp/example' EXIT")))
            assertNull(CommandSafety.dangerousMatch(c), c.toString());
    }
}
