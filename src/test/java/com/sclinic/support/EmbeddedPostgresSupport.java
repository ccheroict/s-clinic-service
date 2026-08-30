package com.sclinic.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Starts a single real PostgreSQL instance for the whole test JVM and hands out
 * an isolated database per test class.
 *
 * <p>Uses a bundled Postgres binary instead of a container: the Docker engine is
 * not available on the build machine, and the Flyway migrations depend on
 * Postgres-specific features (jsonb, {@code gen_random_uuid()}, partial indexes,
 * triggers, role grants) that H2 cannot reproduce. Booting the Spring context
 * against this instance with {@code ddl-auto=validate} is what proves the JPA
 * entities and the Flyway schema agree.
 *
 * <p>Each test class must ask for its own database name. Sharing one database
 * across classes breaks the bootstrap seeders, which are deliberately idempotent
 * and would skip seeding for the second context to start.
 */
public final class EmbeddedPostgresSupport {

    private static final String USERNAME = "postgres";
    private static final String PASSWORD = "postgres";

    private static EmbeddedPostgres instance;

    private EmbeddedPostgresSupport() {
    }

    private static synchronized EmbeddedPostgres instance() {
        if (instance == null) {
            try {
                instance = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    instance.close();
                } catch (IOException ignored) {
                    // JVM is going down; nothing useful to do with this failure.
                }
            }));
        }
        return instance;
    }

    private static String urlFor(String database) {
        return "jdbc:postgresql://localhost:" + instance().getPort() + "/" + database;
    }

    /**
     * JDBC URL for a database dedicated to one test class, created on demand.
     *
     * @param database lowercase identifier, e.g. {@code facility_it}
     */
    public static synchronized String jdbcUrlFor(String database) {
        requireSafeIdentifier(database);
        createDatabaseIfAbsent(database);
        return urlFor(database);
    }

    public static String username() {
        return USERNAME;
    }

    public static String password() {
        return PASSWORD;
    }

    private static void createDatabaseIfAbsent(String database) {
        try (Connection connection = DriverManager.getConnection(urlFor(USERNAME), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {

            try (ResultSet existing = statement.executeQuery(
                    "select 1 from pg_database where datname = '" + database + "'")) {
                if (existing.next()) {
                    return;
                }
            }
            // CREATE DATABASE cannot run inside a transaction; autocommit is on by default.
            statement.executeUpdate("create database " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + database, e);
        }
    }

    /**
     * Database names are interpolated into DDL, which has no parameter binding.
     * Restricting the charset keeps that safe.
     */
    private static void requireSafeIdentifier(String database) {
        if (database == null || !database.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(
                    "Test database name must match [a-z][a-z0-9_]{0,62}, got: " + database);
        }
    }
}
