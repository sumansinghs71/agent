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

    /**
     * Probed ONCE and cached.
     *
     * <p>Previously this shelled out to {@code docker info} on every call, and JUnit calls it both
     * for {@code @EnabledIf} and again inside {@code @BeforeAll}. Under load - another suite using
     * the daemon concurrently - the second probe could time out while the first had succeeded, so
     * the class was enabled but the container was never started, leaving {@code jdbc} null and
     * every test failing with a NullPointerException.
     *
     * <p>A single cached probe makes the two decisions agree by construction.
     */
    private static volatile Boolean dockerAvailable;

    public static synchronized boolean dockerAvailable() {
        if (dockerAvailable == null) {
            try {
                Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
                dockerAvailable = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                dockerAvailable = false;
            }
        }
        return dockerAvailable;
    }

    @BeforeAll
    static void startDatabase() {
        if (postgres != null) {
            return;
        }
        // Reaching @BeforeAll means @EnabledIf already decided Docker is available. If the container
        // then fails to start, fail loudly: silently returning leaves `jdbc` null and every test in
        // the class dies with an unrelated NullPointerException, which hides the real cause.
        if (!dockerAvailable()) {
            throw new IllegalStateException(
                    "Docker was reported available but is not; refusing to run with a null datasource");
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
    void requireDatabase() {
        if (jdbc == null) {
            throw new IllegalStateException(
                    "PostgreSQL was not started. This class is @EnabledIf(dockerAvailable) and must "
                    + "not reach a test method without a live container.");
        }
    }

    @BeforeEach
    void resetDatabase() {
        repo = new RunRepository(jdbc);
        jdbc.execute("TRUNCATE agent_run, idempotency_record RESTART IDENTITY CASCADE");
    }
}
