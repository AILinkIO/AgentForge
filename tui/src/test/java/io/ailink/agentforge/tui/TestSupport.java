package io.ailink.agentforge.tui;

/** 测试公用：英文原文消息、固定的编辑器。 */
final class TestSupport {
    private TestSupport() {}

    static { I18n.setLanguage("en"); }

    /** *scratch* 里放 text，point 在 p。 */
    static Editor fresh(String text, int p) {
        I18n.setLanguage("en");
        Editor ed = new Editor(80, 24);
        ed.currentBuffer().setContents(text);
        ed.currentBuffer().undo().clear();
        ed.goTo(p);
        return ed;
    }

    static Editor fresh(String text) { return fresh(text, 0); }

    static String text(Editor ed) { return ed.currentBuffer().contents(); }
}
