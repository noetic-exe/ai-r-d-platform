package com.aird;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import javax.sql.DataSource;

@SpringBootApplication
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class, args); }
    @Bean(initMethod="migrate") Flyway migrations(DataSource source) { return Flyway.configure().dataSource(source).load(); }
}
