package com.sclinic.facility;

import com.sclinic.facility.dto.FacilityRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean validation on the facility payload. The controller relies on {@code @Valid}
 * to turn these into 400 responses, so the constraints themselves are what
 * guarantee a blank facility code never reaches the database.
 */
class FacilityRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static FacilityRequest withKcbCode(String kcbCode) {
        return new FacilityRequest("Phong kham S-Clinic", kcbCode, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void rejectsBlankKcbCode(String blank) {
        Set<ConstraintViolation<FacilityRequest>> violations = validator.validate(withKcbCode(blank));

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("kcbCode");
    }

    @Test
    void rejectsNullKcbCode() {
        Set<ConstraintViolation<FacilityRequest>> violations = validator.validate(withKcbCode(null));

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("kcbCode");
    }

    @Test
    void acceptsValidKcbCode() {
        Set<ConstraintViolation<FacilityRequest>> violations = validator.validate(withKcbCode("KCB-001"));

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "  "})
    void rejectsBlankName(String blank) {
        FacilityRequest request = new FacilityRequest(blank, "KCB-001", null, null, null, null, null,
                null, null, null, null, null, null);

        Set<ConstraintViolation<FacilityRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void rejectsMalformedEmail() {
        FacilityRequest request = new FacilityRequest("Phong kham S-Clinic", "KCB-001", null, null,
                null, null, "not-an-email", null, null, null, null, null, null);

        Set<ConstraintViolation<FacilityRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("email");
    }
}
