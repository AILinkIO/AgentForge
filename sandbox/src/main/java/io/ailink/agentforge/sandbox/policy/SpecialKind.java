package io.ailink.agentforge.sandbox.policy;

/**
 * 特殊路径：在 {@link PathContext} 里才解析成具体目录。
 * {@code ROOT} = cwd 所在文件系统的根；{@code MINIMAL} = 平台缺省可读目录（Linux 的 /usr、/etc …，由后端补）；
 * {@code PROJECT_ROOTS} = 项目根（缺省 cwd，可带子路径）；{@code TMPDIR} = $TMPDIR；{@code SLASH_TMP} = /tmp（Windows 上无）。
 */
public enum SpecialKind {
    ROOT("root"), MINIMAL("minimal"), PROJECT_ROOTS("project_roots"), TMPDIR("tmpdir"), SLASH_TMP("slash_tmp");

    private final String jsonName;

    SpecialKind(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
