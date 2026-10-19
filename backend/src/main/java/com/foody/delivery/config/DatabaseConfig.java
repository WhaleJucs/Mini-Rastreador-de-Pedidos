package com.foody.delivery.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class DatabaseConfig {

    @Bean
    DataSource dataSource(@Value("${spring.datasource.url}") String url) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(3000);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);
        return dataSource;
    }

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        var manager = new JdbcTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(true);
        return manager;
    }

    @Bean
    DataSourceInitializer schemaInitializer(DataSource dataSource) {
        var initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")));
        return initializer;
    }
}
