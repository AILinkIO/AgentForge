package io.ailink.agentforge.config;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import io.ailink.agentforge.persistence.store.BitcaskHistoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储：bitcask（经 FFM 加载 libbitcask）。库目录 {@code agentforge.data-dir}（默认 ./data）下的 {@code history}。
 *
 * <p>libbitcask 的位置见 {@code BitcaskLibrary}：{@code -Dagentforge.bitcask.lib=…}、环境变量
 * {@code AGENTFORGE_BITCASK_LIB}，或打包进 jar 的 {@code /native/<rid>/}。装不上时启动即报原因。
 */
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    public Bitcask historyDb(@Value("${agentforge.data-dir:./data}") String dataDir) throws Exception {
        String why = BitcaskRuntime.unavailableReason();
        if (why != null) {
            throw new IllegalStateException("libbitcask 装不上：" + why
                    + "（设 AGENTFORGE_BITCASK_LIB=/path/libbitcask.so，或构建时 -Pbitcask.lib=… 打进 jar）");
        }
        Path dir = Path.of(dataDir, "history");
        Files.createDirectories(dir);
        return Bitcask.open(dir);
    }

    @Bean
    public BitcaskHistoryStore historyStore(Bitcask historyDb) {
        return new BitcaskHistoryStore(historyDb);
    }
}
