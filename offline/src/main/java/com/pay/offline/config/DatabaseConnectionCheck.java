package com.pay.offline.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConnectionCheck {
    @Bean
    CommandLineRunner verifyDatabaseConnection(JdbcTemplate jdbcTemplate) {

        return args -> {
            String databaseName = jdbcTemplate.queryForObject(
                    "SELECT DATABASE()",
                    String.class
            );

            String databaseVersion = jdbcTemplate.queryForObject(
                    "SELECT VERSION()",
                    String.class
            );

            System.out.println("====================================");
            System.out.println("MYSQL CONNECTION SUCCESSFUL");
            System.out.println("Database: " + databaseName);
            System.out.println("MySQL Version: " + databaseVersion);
            System.out.println("====================================");
        };
    }
}
