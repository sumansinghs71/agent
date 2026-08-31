package com.chatbot.agent.runtime.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;

/**
 * Base for durable-runtime tests. Runs against real PostgreSQL in a container.
 *
 * <p>Deliberately not H2. The behaviour under test here IS the database's behaviour - conditional
 * updates, {@code ON CONFLICT}, transaction boundaries, concurrent writers. An in-memory emulation
 * of those is an emulation of exactly the thing being verified, so a green suite would prove
 * nothing about production.
 *
 * <p>Skipped, not failed, without a Docker daemon; CI asserts the suite actually ran.
 */
@EnabledIf("dockerAvailable")
public abstract class AbstractPostgresTest {

    private static PostgreSQLContainer<?> postgres;
    protected static JdbcTemplate jdbc;
    protected RunRepository repo;

    public static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void startDatabase() {
        if (!dockerAvailable() || postgres != null) {
            return;
        }
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("agent_runtime")
                .withUsername("agent")
                .withPassword("agent");
        postgres.start();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        cfg.setMaximumPoolSize(8);
        HikariDataSource ds = new HikariDataSource(cfg);

        // The migration under test is the one that ships, not a test-only copy: a schema that
        // diverges from production is a test that verifies fiction.
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void resetDatabase() {
        repo = new RunRepository(jdbc);
        jdbc.execute("TRUNCATE agent_run, idempotency_record RESTART IDENTITY CASCADE");
    }
}
