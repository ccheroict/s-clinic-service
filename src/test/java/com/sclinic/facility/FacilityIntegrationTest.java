package com.sclinic.facility;

import com.sclinic.bootstrap.FacilitySeeder;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test on a real PostgreSQL instance.
 *
 * <p>Booting this context is itself a schema check: {@code ddl-auto=validate}
 * makes Hibernate compare every entity against the Flyway-built schema, so a
 * migration that disagrees with an entity fails here rather than at runtime.
 */
@SpringBootTest(properties = {
        "sclinic.facility.name=Phong kham tich hop",
        "sclinic.facility.kcb-code=KCB-IT-001",
        "sclinic.facility.interop-code=LT-IT-001",
        "logging.level.org.hibernate.SQL=warn"
})
class FacilityIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("facility_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    FacilityRepository repository;

    @Autowired
    FacilitySeeder seeder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationsApplyThroughV4() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);

        assertThat(applied).isGreaterThanOrEqualTo(4);

        Integer v4 = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '4' and success = true",
                Integer.class);
        assertThat(v4).isEqualTo(1);
    }

    @Test
    void seedCreatesExactlyOneFacility() {
        assertThat(repository.count()).isEqualTo(1);

        Facility facility = repository.findFirstByActiveTrueOrderByCreatedAtAsc().orElseThrow();
        assertThat(facility.getKcbCode()).isEqualTo("KCB-IT-001");
        assertThat(facility.getInteropCode()).isEqualTo("LT-IT-001");
        assertThat(facility.isActive()).isTrue();
        assertThat(facility.getCreatedAt()).isNotNull();
        assertThat(facility.getUpdatedAt()).isNotNull();
    }

    @Test
    void seedIsIdempotentAcrossRestarts() {
        long before = repository.count();

        // Simulates two further application restarts.
        seeder.run();
        seeder.run();

        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void blankOptionalConfigIsStoredAsNullNotEmptyString() {
        Facility facility = repository.findFirstByActiveTrueOrderByCreatedAtAsc().orElseThrow();

        // tax-code was not set in this test's properties.
        assertThat(facility.getTaxCode()).isNull();
        assertThat(facility.getEinvoiceSerial()).isNull();
    }

    @Test
    void kcbCodeIsUniqueAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into facility (name, kcb_code) values (?, ?)",
                "Ban sao trung ma", "KCB-IT-001"))
                .hasMessageContaining("duplicate key");
    }

    @Test
    void facilityRejectsMissingRequiredColumnsAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into facility (kcb_code) values (?)", "KCB-NO-NAME"))
                .hasMessageContaining("name");
    }
}
