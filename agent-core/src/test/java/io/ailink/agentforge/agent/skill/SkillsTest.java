package io.ailink.agentforge.agent.skill;

import io.ailink.agentforge.agent.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static io.ailink.agentforge.agent.util.ToolKit.put;
import static org.junit.jupiter.api.Assertions.*;

class SkillsTest {
    @TempDir Path proj;
    @TempDir Path user;

    @Test
    void frontmatter() {
        Skills.Frontmatter f = Skills.parseFrontmatter("---\nname: deploy\ndescription: 发一版\n---\n正文\n第二行");
        assertEquals("deploy", f.fields().get("name"));
        assertEquals("发一版", f.fields().get("description"));
        assertEquals("正文\n第二行", f.body());
        Skills.Frontmatter none = Skills.parseFrontmatter("没有 frontmatter\n---\n后面");
        assertTrue(none.fields().isEmpty());
        assertEquals("没有 frontmatter\n---\n后面", none.body());
        Skills.Frontmatter open = Skills.parseFrontmatter("---\nname: x\n没闭合");
        assertTrue(open.fields().isEmpty());
        assertEquals("---\nname: x\n没闭合", open.body());
        Skills.Frontmatter fold = Skills.parseFrontmatter("---\ndescription: >\n  第一行，\n  第二行。\nname: \"quoted\"\nurl: https://x.y/z:80\n---\n");
        assertEquals("第一行， 第二行。", fold.fields().get("description"));
        assertEquals("quoted", fold.fields().get("name"));
        assertEquals("https://x.y/z:80", fold.fields().get("url"));
        assertEquals(1, Skills.parseFrontmatter("---\n认不出\nk: v\n---\n").fields().size());
    }

    private void fixtures() throws Exception {
        put(proj.resolve("deploy/SKILL.md"), "---\nname: deploy\ndescription: 项目里的发版法\n---\n1. ctest\n2. tag\n");
        put(proj.resolve("deploy/scripts/release.sh"), "echo hi");
        put(proj.resolve("noname/SKILL.md"), "---\ndescription: 用目录名\n---\nbody");
        put(proj.resolve("nodesc/SKILL.md"), "---\nname: nodesc\n---\nbody");
        put(proj.resolve("bad name/SKILL.md"), "body");
        Files.createDirectories(proj.resolve("notaskill"));
        Files.createDirectories(proj.resolve("bin"));
        Files.write(proj.resolve("bin/SKILL.md"), new byte[]{0, 1, 2, 0});
        put(user.resolve("deploy/SKILL.md"), "---\nname: deploy\ndescription: 用户的发版法\n---\nx");
        put(user.resolve("pdf/SKILL.md"), "---\nname: pdf\ndescription: 抽文本\n第二行\n---\nx");
    }

    @Test
    void scanAndFind() throws Exception {
        fixtures();
        List<Skills.Skill> found = Skills.scan(List.of(proj, proj.resolve("missing"), user));
        assertEquals(List.of("deploy", "nodesc", "noname", "pdf"), found.stream().map(Skills.Skill::name).toList());
        assertEquals("项目里的发版法", Skills.find(found, "deploy").description(), "同名先到的赢");
        assertEquals("用目录名", Skills.find(found, "noname").description());
        assertEquals("（没有描述）", Skills.find(found, "nodesc").description());
        assertNull(Skills.find(found, "bad name"));
        assertNull(Skills.find(found, "notaskill"));
        assertNull(Skills.find(found, "bin"));
        assertEquals(List.of(), Skills.scan(List.of()));
        List<Path> dirs = Skills.dirs(proj);
        assertTrue(dirs.contains(proj.resolve(".claude").resolve("skills")));
        assertTrue(dirs.indexOf(proj.resolve(".agentforge").resolve("skills")) < dirs.indexOf(Path.of(System.getProperty("user.home"), ".claude", "skills")));
    }

    @Test
    void promptSectionAndLoad() throws Exception {
        fixtures();
        List<Skills.Skill> found = Skills.scan(List.of(proj, user));
        assertEquals("", Skills.promptSection(List.<Skills.Skill>of()));
        String s = Skills.promptSection(found);
        assertTrue(s.contains("## 可用的 skill"));
        assertTrue(s.contains("- deploy：项目里的发版法"));
        assertTrue(s.contains("skill 工具"));
        String longDesc = Skills.promptSection(List.of(new Skills.Skill("x", "a".repeat(300) + "\n第二行", Path.of("x"), Path.of("."))));
        assertFalse(longDesc.contains("第二行"));
        assertTrue(longDesc.contains("a".repeat(200) + "…"));
        String l = Skills.load(Skills.find(found, "deploy"));
        assertTrue(l.contains("1. ctest"));
        assertFalse(l.contains("description:"));
        assertTrue(l.contains("基目录：" + proj.resolve("deploy")));
        assertTrue(l.contains("- " + proj.resolve("deploy/scripts/release.sh")));
        assertFalse(l.contains("- " + proj.resolve("deploy/SKILL.md")));
    }

    @Test
    void tool() throws Exception {
        put(proj.resolve(".agentforge/skills/deploy/SKILL.md"), "---\nname: deploy\ndescription: d\n---\n1. ctest\n");
        ToolOutput ok = call(SkillTool.tool(), proj, "name", "deploy");
        assertFalse(ok.error(), ok.text());
        assertTrue(ok.text().startsWith("已装载 skill deploy\n"));
        assertTrue(ok.text().contains("1. ctest"));
        ToolOutput bad = call(SkillTool.tool(), proj, "name", "nope");
        assertTrue(bad.error());
        assertTrue(bad.text().contains("有的是") && bad.text().contains("deploy"), bad.text());
        assertTrue(SkillTool.tool().readOnly());
        assertFalse(SkillTool.tool().sensitive());
    }
}
