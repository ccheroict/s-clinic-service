package com.sclinic.encounter;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema either means something or it does not.
 *
 * <p>Requirement 13.4 asks for invalid clinical data to be refused. The reason
 * it needs a property test rather than a handful of examples is that the failure
 * mode is silent: data that does not match the schema still stores perfectly
 * well in a {@code jsonb} column, so a validator that passes something it should
 * not refuse leaves no trace until someone tries to use the field and finds
 * "very bad" where a severity grade was supposed to be.
 *
 * <p>The two properties are the two halves of that: anything the schema allows
 * must always be accepted, and anything it forbids must always be refused.
 */
class ClinicalDataSchemaPropertyTest {

    private final ClinicalDataValidator validator =
            new ClinicalDataValidator(new ObjectMapper());

    /** The dermatology schema as V16 seeds it, kept in step with the migration. */
    private ClinicalDataSchema dermatologySchema() {
        Map<String, Object> schema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of(
                        "lesionDescription", Map.of("type", "string", "maxLength", 4000),
                        "lesionTypes", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string",
                                        "enum", List.of("MACULE", "PAPULE", "PLAQUE", "NODULE")),
                                "uniqueItems", true),
                        "bodySurfaceAreaPercent", Map.of(
                                "type", "number", "minimum", 0, "maximum", 100),
                        "severity", Map.of(
                                "type", "string",
                                "enum", List.of("MILD", "MODERATE", "SEVERE")),
                        "itchScore", Map.of(
                                "type", "integer", "minimum", 0, "maximum", 10)),
                "additionalProperties", false);

        ClinicalDataSchema entity = new ClinicalDataSchema();
        entity.setId(UUID.randomUUID());
        entity.setSpecialtyCode("DERMATOLOGY");
        entity.setVersion(1);
        entity.setTitle("Khám da liễu");
        entity.setSchema(schema);
        return entity;
    }

    // ---------- valid data is always accepted ----------

    @Property(tries = 200)
    void anyCombinationOfValidFieldsIsAccepted(
            @ForAll("validSeverity") String severity,
            @ForAll @IntRange(min = 0, max = 10) int itchScore,
            @ForAll @IntRange(min = 0, max = 100) int bsa) {

        Map<String, Object> data = new HashMap<>();
        data.put("severity", severity);
        data.put("itchScore", itchScore);
        data.put("bodySurfaceAreaPercent", bsa);
        data.put("lesionDescription", "Mảng đỏ bong vảy");

        assertThatCode(() -> validator.validate(data, dermatologySchema()))
                .doesNotThrowAnyException();
    }

    @Property(tries = 100)
    void anySubsetOfTheFieldsIsAccepted(@ForAll("fieldSubset") Map<String, Object> data) {
        // No field is required. A draft is written a field at a time, and a
        // schema that demanded the whole form would stop the record being saved
        // until the examination was over — which is how notes end up on paper
        // and typed in later, or not at all.
        assertThatCode(() -> validator.validate(data, dermatologySchema()))
                .doesNotThrowAnyException();
    }

    // ---------- invalid data is always refused ----------

    @Property(tries = 200)
    void aSeverityOutsideTheEnumIsAlwaysRefused(@ForAll("invalidSeverity") String severity) {
        Map<String, Object> data = Map.of("severity", severity);

        // The case that motivates the whole mechanism: free text where a grade
        // belongs stores fine and means nothing.
        assertThatThrownBy(() -> validator.validate(data, dermatologySchema()))
                .isInstanceOf(ClinicalDataInvalidException.class);
    }

    @Property(tries = 200)
    void anItchScoreOutsideTheRangeIsAlwaysRefused(@ForAll("outOfRangeScore") int score) {
        Map<String, Object> data = Map.of("itchScore", score);

        assertThatThrownBy(() -> validator.validate(data, dermatologySchema()))
                .isInstanceOf(ClinicalDataInvalidException.class);
    }

    @Property(tries = 100)
    void aBodySurfaceAreaOutsideZeroToAHundredIsAlwaysRefused(
            @ForAll("impossibleBsa") int bsa) {
        Map<String, Object> data = Map.of("bodySurfaceAreaPercent", bsa);

        assertThatThrownBy(() -> validator.validate(data, dermatologySchema()))
                .isInstanceOf(ClinicalDataInvalidException.class);
    }

    @Property(tries = 100)
    void anUnknownFieldIsAlwaysRefused(@ForAll("unknownFieldName") String field) {
        Map<String, Object> data = Map.of(field, "anything");

        // additionalProperties is false on purpose. A mistyped field name writes
        // a field nothing will ever read, and the typo is invisible: the record
        // looks saved, the data is gone.
        assertThatThrownBy(() -> validator.validate(data, dermatologySchema()))
                .isInstanceOf(ClinicalDataInvalidException.class);
    }

    @Property(tries = 100)
    void aWrongTypeIsAlwaysRefused(@ForAll("notANumber") String value) {
        Map<String, Object> data = Map.of("itchScore", value);

        assertThatThrownBy(() -> validator.validate(data, dermatologySchema()))
                .isInstanceOf(ClinicalDataInvalidException.class);
    }

    // ---------- shape of the refusal ----------

    @Test
    void everyFailureIsReportedNotJustTheFirst() {
        Map<String, Object> data = Map.of(
                "severity", "khong-biet",
                "itchScore", 99,
                "khongCoTruong", "x");

        ClinicalDataInvalidException thrown = null;
        try {
            validator.validate(data, dermatologySchema());
        } catch (ClinicalDataInvalidException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        // Three separate problems, reported together. One at a time is how a
        // clinician gives up on the form.
        assertThat(thrown.getFailures()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void theRefusalNamesTheSchemaAndVersionItWasCheckedAgainst() {
        Map<String, Object> data = Map.of("severity", "sai");

        assertThatThrownBy(() -> validator.validate(data, dermatologySchema()))
                .hasMessageContaining("DERMATOLOGY")
                .hasMessageContaining("1");
    }

    @Test
    void emptyAndNullClinicalDataAreAccepted() {
        // A record opened before the examination starts has no clinical data, and
        // refusing that would mean the record could not exist until it was
        // finished.
        assertThatCode(() -> validator.validate(null, dermatologySchema()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(Map.of(), dermatologySchema()))
                .doesNotThrowAnyException();
    }

    // ---------- an older version keeps validating its own records ----------

    @Test
    void dataValidUnderAnOlderVersionStaysValidUnderThatVersion() {
        // Requirement 13.5. Version 2 removes a field and adds a required one;
        // a record written under version 1 is still checked against version 1,
        // and must still pass.
        ClinicalDataSchema v1 = dermatologySchema();

        ClinicalDataSchema v2 = new ClinicalDataSchema();
        v2.setId(UUID.randomUUID());
        v2.setSpecialtyCode("DERMATOLOGY");
        v2.setVersion(2);
        v2.setTitle("Khám da liễu bản 2");
        v2.setSchema(Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of("newMandatoryField", Map.of("type", "string")),
                "required", List.of("newMandatoryField"),
                "additionalProperties", false));

        Map<String, Object> writtenUnderV1 = Map.of("severity", "MILD", "itchScore", 3);

        assertThatCode(() -> validator.validate(writtenUnderV1, v1))
                .doesNotThrowAnyException();

        // And the same data against the new version fails, which is why the row
        // has to remember which version applied rather than being re-checked
        // against whatever is current.
        assertThatThrownBy(() -> validator.validate(writtenUnderV1, v2))
                .isInstanceOf(ClinicalDataInvalidException.class);
    }

    // ---------- generators ----------

    @Provide
    Arbitrary<String> validSeverity() {
        return Arbitraries.of("MILD", "MODERATE", "SEVERE");
    }

    @Provide
    Arbitrary<String> invalidSeverity() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(20)
                .filter(s -> !List.of("MILD", "MODERATE", "SEVERE").contains(s));
    }

    @Provide
    Arbitrary<Integer> outOfRangeScore() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(-1000, -1),
                Arbitraries.integers().between(11, 1000));
    }

    @Provide
    Arbitrary<Integer> impossibleBsa() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(-1000, -1),
                Arbitraries.integers().between(101, 1000));
    }

    @Provide
    Arbitrary<String> unknownFieldName() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .filter(name -> !List.of("lesionDescription", "lesionTypes",
                        "bodySurfaceAreaPercent", "severity", "itchScore").contains(name));
    }

    @Provide
    Arbitrary<String> notANumber() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    @Provide
    Arbitrary<Map<String, Object>> fieldSubset() {
        return Arbitraries.subsetOf("lesionDescription", "severity", "itchScore",
                        "bodySurfaceAreaPercent")
                .map(fields -> {
                    Map<String, Object> data = new HashMap<>();
                    for (String field : fields) {
                        data.put(field, switch (field) {
                            case "lesionDescription" -> "Mô tả";
                            case "severity" -> "MODERATE";
                            case "itchScore" -> 5;
                            default -> 20;
                        });
                    }
                    return data;
                });
    }
}
