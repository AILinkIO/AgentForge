package io.ailink.agentforge.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(DataSourceProperties properties) {
        String url = properties.getUrl();
        if (url != null && url.startsWith("jdbc:sqlite:")) {
            String path = url.replace("jdbc:sqlite:", "");
            File parentDir = new File(path).getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
        }
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
