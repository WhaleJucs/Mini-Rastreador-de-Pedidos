package com.foody.delivery.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@Import(EnvironmentValidator.class)
public class DatabaseConfig {

    @Bean
    DataSource dataSource(AppProperties properties, EnvironmentValidator validatedEnvironment, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            createLocalDatabaseDirectory(properties.db().path());
        }
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(properties.db().busyTimeoutMs());
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + properties.db().path());
        return dataSource;
    }

    private static void createLocalDatabaseDirectory(String databasePath) {
        var parent = Path.of(databasePath).toAbsolutePath().normalize().getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new IllegalStateException("Não foi possível preparar o diretório do banco local.", failure);
        }
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
