package com.sclinic.audit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical form the audit chain hashes.
 *
 * <p>These tests pin down what must change the hash and what must not. If one of
 * them starts failing after a refactor, every audit entry written before the
 * refactor stops verifying, so the canonical form is effectively frozen once
 * entries exist in production.
 */
class AuditHashTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant AT = Instant.parse("2026-08-29T03:00:00.123456Z");

    private String hash(String prevHash, UUID staffId, String action, String entityType, UUID entityId,
                        Map<String, Object> detail, String ip, String userAgent, UUID sessionId,
                        Instant createdAt) {
        return AuditHash.of(prevHash, staffId, action, entityType, entityId, detail, ip, userAgent,
                sessionId, createdAt);
    }

    private String baseline() {
        return hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                "10.0.0.5", "JUnit", SESSION, AT);
    }

    @Test
    void isDeterministic() {
        assertThat(baseline()).isEqualTo(baseline());
    }

    @Test
    void looksLikeASha256Digest() {
        assertThat(baseline()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Nested
    class EveryFieldIsCovered {

        @Test
        void previousHash() {
            assertThat(hash("other", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.5", "JUnit", SESSION, AT)).isNotEqualTo(baseline());
        }

        @Test
        void staffId() {
            assertThat(hash("prev", UUID.randomUUID(), "UPDATE", "patient", ENTITY,
                    Map.of("name", "changed"), "10.0.0.5", "JUnit", SESSION, AT))
                    .isNotEqualTo(baseline());
        }

        @Test
        void action() {
            assertThat(hash("prev", STAFF, "VIEW", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.5", "JUnit", SESSION, AT)).isNotEqualTo(baseline());
        }

        @Test
        void entityType() {
            assertThat(hash("prev", STAFF, "UPDATE", "appointment", ENTITY,
                    Map.of("name", "changed"), "10.0.0.5", "JUnit", SESSION, AT))
                    .isNotEqualTo(baseline());
        }

        @Test
        void entityId() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", UUID.randomUUID(),
                    Map.of("name", "changed"), "10.0.0.5", "JUnit", SESSION, AT))
                    .isNotEqualTo(baseline());
        }

        @Test
        void detail() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("phone", "changed"),
                    "10.0.0.5", "JUnit", SESSION, AT)).isNotEqualTo(baseline());
        }

        @Test
        void ip() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.6", "JUnit", SESSION, AT)).isNotEqualTo(baseline());
        }

        @Test
        void userAgent() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.5", "Other", SESSION, AT)).isNotEqualTo(baseline());
        }

        @Test
        void sessionId() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.5", "JUnit", UUID.randomUUID(), AT)).isNotEqualTo(baseline());
        }

        @Test
        void createdAt() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.5", "JUnit", SESSION, AT.plusMillis(1))).isNotEqualTo(baseline());
        }

        @Test
        void createdAtDownToTheMicrosecond() {
            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of("name", "changed"),
                    "10.0.0.5", "JUnit", SESSION, AT.plus(1, ChronoUnit.MICROS)))
                    .isNotEqualTo(baseline());
        }
    }

    @Nested
    class Ambiguity {

        /**
         * A null field and an empty one must hash differently, otherwise a value
         * could be blanked out without breaking the chain.
         */
        @Test
        void nullIsNotTheSameAsEmpty() {
            String withNullIp = hash("prev", STAFF, "UPDATE", "patient", ENTITY, null,
                    null, "JUnit", SESSION, AT);
            String withEmptyIp = hash("prev", STAFF, "UPDATE", "patient", ENTITY, null,
                    "", "JUnit", SESSION, AT);

            assertThat(withNullIp).isNotEqualTo(withEmptyIp);
        }

        @Test
        void noDetailIsNotTheSameAsAnEmptyDetail() {
            String withNull = hash("prev", STAFF, "UPDATE", "patient", ENTITY, null,
                    "10.0.0.5", "JUnit", SESSION, AT);
            String withEmptyMap = hash("prev", STAFF, "UPDATE", "patient", ENTITY, Map.of(),
                    "10.0.0.5", "JUnit", SESSION, AT);

            // Both mean "nothing recorded", so they are allowed to agree; what
            // matters is that neither collides with an actual detail.
            assertThat(withNull).isEqualTo(withEmptyMap);
            assertThat(withNull).isNotEqualTo(baseline());
        }

        /**
         * Field contents must not be able to imitate the boundary between
         * fields, whatever separator the canonical form happens to use. If they
         * could, two different entries would share a hash and one could be
         * rewritten as the other without breaking the chain.
         */
        @Test
        void contentCannotForgeAFieldBoundary() {
            String honest = hash("prev", STAFF, "UPDATE", "patient", ENTITY, null,
                    "10.0.0.5", "JUnit", SESSION, AT);

            for (String separator : new String[]{"\u001e", "\u001f", "|", ":", "\n", "\u0000"}) {
                String shifted = hash("prev", STAFF, "UPDATE", "patient", ENTITY, null,
                        "10.0.0.5" + separator + "JUnit", null, SESSION, AT);

                assertThat(shifted)
                        .as("field content joined by %s must not collide", separator)
                        .isNotEqualTo(honest);
            }
        }

        /**
         * Same argument one level down: a value must not be able to imitate the
         * boundary between a detail key and its value.
         */
        @Test
        void detailContentCannotForgeAKeyBoundary() {
            String twoKeys = hash("prev", STAFF, "UPDATE", "patient", ENTITY,
                    Map.of("a", "b", "c", "d"), null, null, null, AT);

            for (String separator : new String[]{"\u001f", "|", ":", ""}) {
                String oneKey = hash("prev", STAFF, "UPDATE", "patient", ENTITY,
                        Map.of("a", "b" + separator + "c" + separator + "d"), null, null, null, AT);

                assertThat(oneKey)
                        .as("detail packed with %s must not collide", separator)
                        .isNotEqualTo(twoKeys);
            }
        }

        /**
         * A nested map must not collide with a string written to look like one.
         * The candidates below are the encodings a plain string would have to
         * produce to pass itself off as the nested map above.
         */
        @Test
        void nestedDetailCannotBeImitatedByAString() {
            String nested = hash("prev", STAFF, "UPDATE", "appointment", ENTITY,
                    Map.of("status", Map.of("from", "BOOKED")), null, null, null, AT);

            for (String imitation : new String[]{
                    "map:4:from6:BOOKED",
                    "m4:from6:BOOKED",
                    "1:m15:4:from6:BOOKED",
                    "4:from6:BOOKED"}) {

                String flat = hash("prev", STAFF, "UPDATE", "appointment", ENTITY,
                        Map.of("status", imitation), null, null, null, AT);

                assertThat(flat)
                        .as("string %s must not collide with the nested map", imitation)
                        .isNotEqualTo(nested);
            }
        }
    }

    @Nested
    class DetailCanonicalisation {

        /**
         * A jsonb column read back does not promise the insertion order it was
         * written with, so key order must not affect the hash.
         */
        @Test
        void ignoresKeyOrder() {
            Map<String, Object> oneOrder = new LinkedHashMap<>();
            oneOrder.put("name", "changed");
            oneOrder.put("phone", "changed");

            Map<String, Object> otherOrder = new LinkedHashMap<>();
            otherOrder.put("phone", "changed");
            otherOrder.put("name", "changed");

            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, oneOrder,
                    "10.0.0.5", "JUnit", SESSION, AT))
                    .isEqualTo(hash("prev", STAFF, "UPDATE", "patient", ENTITY, otherOrder,
                            "10.0.0.5", "JUnit", SESSION, AT));
        }

        @Test
        void coversNestedValues() {
            Map<String, Object> before = Map.of("status",
                    Map.of("from", "BOOKED", "to", "CONFIRMED"));
            Map<String, Object> after = Map.of("status",
                    Map.of("from", "BOOKED", "to", "CANCELLED"));

            assertThat(hash("prev", STAFF, "UPDATE", "appointment", ENTITY, before,
                    "10.0.0.5", "JUnit", SESSION, AT))
                    .isNotEqualTo(hash("prev", STAFF, "UPDATE", "appointment", ENTITY, after,
                            "10.0.0.5", "JUnit", SESSION, AT));
        }

        @Test
        void distinguishesANullValueFromAnEmptyOne() {
            java.util.Map<String, Object> withNull = new LinkedHashMap<>();
            withNull.put("note", null);

            assertThat(hash("prev", STAFF, "UPDATE", "patient", ENTITY, withNull,
                    null, null, null, AT))
                    .isNotEqualTo(hash("prev", STAFF, "UPDATE", "patient", ENTITY,
                            Map.of("note", ""), null, null, null, AT));
        }
    }

    @Test
    void reproducesTheHashOfAStoredEntry() {
        AuditLog entry = new AuditLog();
        entry.setStaffId(STAFF);
        entry.setAction("UPDATE");
        entry.setEntityType("patient");
        entry.setEntityId(ENTITY);
        entry.setDetail(Map.of("name", "changed"));
        entry.setIp("10.0.0.5");
        entry.setUserAgent("JUnit");
        entry.setSessionId(SESSION);
        entry.setCreatedAt(AT);

        assertThat(AuditHash.of("prev", entry)).isEqualTo(baseline());
    }
}
