package io.ailink.agentforge.agent.models;

import java.util.List;

/**
 * provider 表里的一家（已启用的）。
 *
 * @param baseUrl   落进表时定下的端点（用户写的 / 选中时填的 / 目录 {@code api} 字段）；null = 用已知默认或 SDK
 *                  默认。**实际会用哪个**问 {@link Models#endpoint(String)}（那里还有环境变量覆盖）
 * @param apiKeyEnv 显式指定读哪个环境变量（只认这一个）；null = 按已知表 + 目录 {@code env}
 * @param envNames  key 的候选环境变量（已解析好的顺序）
 * @param npm       models.dev 的 npm 包名（只当线索）
 * @param origin    这一家是怎么进表的
 */
public record ProviderInfo(
        String id,
        String label,
        ApiKind api,
        String baseUrl,
        String apiKeyEnv,
        Integer maxTokens,
        List<String> envNames,
        String npm,
        Origin origin) {

    /** DEFINED = 配置里定义的（自有的、定义即启用）；SELECTED = 界面里选中的（记在 state）。 */
    public enum Origin { DEFINED, SELECTED }

    public ProviderInfo {
        envNames = envNames == null ? List.of() : List.copyOf(envNames);
    }
}
