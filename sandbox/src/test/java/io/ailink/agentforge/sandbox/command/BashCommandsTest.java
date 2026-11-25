package io.ailink.agentforge.sandbox.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/shell-command/src/bash.rs 的测试。 */
class BashCommandsTest {
    private static List<List<String>> seq(String src) {
        return BashCommands.parsePlainCommands(src);
    }

    @Test
    void acceptsSingleSimpleCommand() {
        assertEquals(List.of(List.of("ls", "-1")), seq("ls -1"));
    }

    @Test
    void acceptsMultipleCommandsWithAllowedOperators() {
        assertEquals(List.of(List.of("ls"), List.of("pwd"), List.of("echo", "hi there"), List.of("wc", "-l")),
                seq("ls && pwd; echo 'hi there' | wc -l"));
    }

    @Test
    void extractsDoubleAndSingleQuotedStrings() {
        assertEquals(List.of(List.of("echo", "hello world")), seq("echo \"hello world\""));
        assertEquals(List.of(List.of("echo", "hi there")), seq("echo 'hi there'"));
    }

    @Test
    void acceptsDoubleQuotedStringsWithNewlines() {
        assertEquals(List.of(List.of("git", "commit", "-m", "line1\nline2")), seq("git commit -m \"line1\nline2\""));
    }

    @Test
    void acceptsMixedQuoteConcatenation() {
        assertEquals(List.of(List.of("echo", "/usr/local/bin")), seq("echo \"/usr\"'/'\"local\"/bin"));
        assertEquals(List.of(List.of("echo", "/usr/local/bin")), seq("echo '/usr'\"/\"'local'/bin"));
    }

    @Test
    void rejectsDoubleQuotedStringsWithExpansions() {
        assertNull(seq("echo \"hi ${USER}\""));
        assertNull(seq("echo \"$HOME\""));
    }

    @Test
    void acceptsNumbersAsWords() {
        assertEquals(List.of(List.of("echo", "123", "456")), seq("echo 123 456"));
    }

    @Test
    void rejectsParenthesesAndSubshells() {
        assertNull(seq("(ls)"));
        assertNull(seq("ls || (pwd && echo hi)"));
    }

    @Test
    void rejectsRedirectionsAndUnsupportedOperators() {
        assertNull(seq("ls > out.txt"));
        assertNull(seq("echo hi & echo bye"));
    }

    @Test
    void rejectsCommandAndProcessSubstitutionsAndExpansions() {
        assertNull(seq("echo $(pwd)"));
        assertNull(seq("echo `pwd`"));
        assertNull(seq("echo $HOME"));
        assertNull(seq("echo \"hi $USER\""));
    }

    @Test
    void rejectsRuntimeExpansionInPlainWords() {
        for (String script : List.of("find . -{delete,print}", "rg --pre{=,=sh} pattern payload.sh", "find . -del*", "find . -delet?",
                "find . -delet[e]", "find . -de\\lete", "echo ~", "echo ~HOME", "echo HEAD~1", "echo HEAD^", "echo file~", "echo =sh",
                "echo foo^bar", "echo foo#bar", "l* -l")) {
            for (String shell : List.of("bash", "zsh"))
                for (String flag : List.of("-c", "-lc"))
                    assertNull(BashCommands.parseShellLcPlainCommands(List.of(shell, flag, script)), script);
        }
    }

    @Test
    void preservesQuotedLiterals() {
        assertEquals(List.of(List.of("rg", "-g*.py", "pattern")), seq("rg -g\"*.py\" pattern"));
        assertEquals(List.of(List.of("echo", "\\n")), seq("echo \"\\n\""));
        assertEquals(List.of(List.of("echo", "~HOME", "HEAD~1", "HEAD^", "foo#bar", "=sh", "file~")),
                seq("echo \"~HOME\" 'HEAD~1' \"HEAD^\" 'foo#bar' \"=sh\" 'file~'"));
        assertEquals(List.of(List.of("echo", "-{a,b}", "*?[]~^#=\\\\")), seq("echo -\"{a,b}\" '*?[]~^#=\\\\'"));
    }

    @Test
    void rejectsDoubleQuotedEscapes() {
        for (String script : List.of("echo \"\\$HOME\\`\\\"\\\\\\n\"", "find . \"-de\\\nlete\"", "echo \"\\\\\"")) {
            assertNull(seq(script), script);
            for (String shell : List.of("bash", "zsh")) assertNull(BashCommands.parseShellLcPlainCommands(List.of(shell, "-lc", script)), script);
        }
    }

    @Test
    void rejectsVariableAssignmentPrefix() {
        assertNull(seq("FOO=bar ls"));
    }

    @Test
    void rejectsEmptyCommandPositions() {
        assertNull(seq("ls &&"));
        assertNull(seq("&& ls"));
        assertNull(seq("ls ;; pwd"));
        assertNull(seq("ls | | wc"));
    }

    @Test
    void parseZshLcPlainCommands() {
        assertEquals(List.of(List.of("ls")), BashCommands.parseShellLcPlainCommands(List.of("zsh", "-lc", "ls")));
    }

    @Test
    void acceptsConcatenatedFlagAndValue() {
        assertEquals(List.of(List.of("rg", "-n", "foo", "-g*.py")), seq("rg -n \"foo\" -g\"*.py\""));
        assertEquals(List.of(List.of("grep", "-n", "pattern", "-g*.txt")), seq("grep -n 'pattern' -g'*.txt'"));
    }

    @Test
    void rejectsConcatenationWithSubstitution() {
        assertNull(seq("rg -g\"$VAR\" pattern"));
        assertNull(seq("rg -g\"${VAR}\" pattern"));
        assertNull(seq("rg -g\"$(pwd)\" pattern"));
        assertNull(seq("rg -g\"$(echo '*.py')\" pattern"));
    }

    @Test
    void emptyAndWhitespaceScriptsParseToNothing() {
        assertEquals(List.of(), seq(""));
        assertEquals(List.of(), seq("  \n\t  "));
    }

    @Test
    void controlFlowIsNotPlain() {
        assertNull(seq("if true; then ls; fi"));
        assertNull(seq("! ls"));
    }

    // ── 宽松：字面命令 ───────────────────────────────────────────────

    private static List<List<String>> lits(String script) {
        return BashCommands.parseShellLcLiteralCommands(List.of("bash", "-lc", script));
    }

    @Test
    void literalCommandsSeeThroughControlFlowAndSubstitution() {
        assertEquals(List.of(List.of("test", "-d", "/tmp/x"), List.of("rm", "--force", "/tmp/x")),
                lits("if test -d /tmp/x; then rm --force /tmp/x; fi"));
        assertEquals(List.of(List.of("rm", "-rf")), lits("rm -rf \"$TARGET\" >/dev/null"));
        assertTrue(lits("echo \"$(rm -rf /tmp/example)\"").contains(List.of("rm", "-rf", "/tmp/example")));
        assertEquals(List.of(List.of("rm", "-r", "-f")), lits("for t in /tmp/a /tmp/b; do rm -r -f \"$t\"; done"));
        assertEquals(List.of(List.of("echo", "hi")), lits("cmd=rm; $cmd -rf /tmp/x; echo hi"));
        assertTrue(lits("cat <<'EOF' > /tmp/out\nrm -rf /\nEOF\nls").contains(List.of("ls")));
        assertFalse(lits("cat <<'EOF' > /tmp/out\nrm -rf /\nEOF\nls").contains(List.of("rm", "-rf", "/")), "heredoc 正文不是命令");
    }

    @Test
    void literalCommandsRejectSyntaxErrors() {
        assertNull(lits("if then rm -rf /tmp/example"));
        assertNull(lits("echo 'unterminated"));
        assertNull(lits("ls &&"));
        assertNull(lits("for x in a; do echo"));
    }
}
