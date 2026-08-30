package com.sclinic.security.password;

import com.sclinic.security.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    private static final UUID STAFF_ID = UUID.randomUUID();

    private PasswordEncoder encoder;
    private StaffPasswordHistoryRepository historyRepository;
    private PasswordPolicy policy;

    @BeforeEach
    void setUp() {
        // Real bcrypt at the cheapest cost: the reuse checks depend on genuine
        // hash comparison, which a stubbed encoder would not exercise.
        encoder = new BCryptPasswordEncoder(4);
        historyRepository = mock(StaffPasswordHistoryRepository.class);
        AuthProperties properties = new AuthProperties(
                Duration.ofHours(8), Duration.ofMinutes(10), 5, Duration.ofMinutes(15), 3, 12,
                java.util.Set.of(), 1, 10, "S-Clinic");
        policy = new PasswordPolicy(properties, encoder, historyRepository);
    }

    private void historyContains(String... rawPasswords) {
        List<StaffPasswordHistory> entries = new ArrayList<>();
        for (String raw : rawPasswords) {
            StaffPasswordHistory entry = new StaffPasswordHistory();
            entry.setStaffId(STAFF_ID);
            entry.setPasswordHash(encoder.encode(raw));
            entries.add(entry);
        }
        when(historyRepository.findByStaffIdOrderByCreatedAtDesc(eq(STAFF_ID), any(Limit.class)))
                .thenReturn(entries);
    }

    @Nested
    class StrengthTests {

        @Test
        void acceptsLongPasswordWithThreeCharacterClasses() {
            assertThatCode(() -> policy.validateStrength("ChungKham2026"))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsPassphraseWithSpecialCharacters() {
            assertThatCode(() -> policy.validateStrength("phong-kham-DA-LIEU!"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Abc1!", "Short1!x", "Kham2026!Ab"})
        void rejectsPasswordsBelowMinimumLength(String tooShort) {
            assertThatThrownBy(() -> policy.validateStrength(tooShort))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageContaining("at least 12 characters");
        }

        @Test
        void rejectsPasswordWithOnlyTwoCharacterClasses() {
            assertThatThrownBy(() -> policy.validateStrength("chungkhamdalieu2026"))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageContaining("at least 3 of");
        }

        @Test
        void rejectsSingleClassPassword() {
            assertThatThrownBy(() -> policy.validateStrength("aaaaaaaaaaaaaaaaaa"))
                    .isInstanceOf(WeakPasswordException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void rejectsBlankPassword(String blank) {
            assertThatThrownBy(() -> policy.validateStrength(blank))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        void rejectsNullPassword() {
            assertThatThrownBy(() -> policy.validateStrength(null))
                    .isInstanceOf(WeakPasswordException.class);
        }

        @Test
        void messageNeverEchoesThePassword() {
            String secret = "onlylowercaseletters";
            assertThatThrownBy(() -> policy.validateStrength(secret))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageNotContaining(secret);
        }
    }

    @Nested
    class ReuseTests {

        @Test
        void rejectsReuseOfCurrentPassword() {
            String current = "ChungKham2026";
            historyContains();

            assertThatThrownBy(() -> policy.validateForStaff(
                    STAFF_ID, encoder.encode(current), current))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageContaining("differ from the current one");
        }

        @Test
        void rejectsReuseOfPasswordInHistory() {
            historyContains("OldKham2026!", "OlderKham2025!");

            assertThatThrownBy(() -> policy.validateForStaff(
                    STAFF_ID, encoder.encode("CurrentKham2027!"), "OldKham2026!"))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageContaining("last 3 passwords");
        }

        @Test
        void acceptsPasswordNotUsedBefore() {
            historyContains("OldKham2026!", "OlderKham2025!");

            assertThatCode(() -> policy.validateForStaff(
                    STAFF_ID, encoder.encode("CurrentKham2027!"), "BrandNewKham2028!"))
                    .doesNotThrowAnyException();
        }

        @Test
        void stillEnforcesStrengthWhenCheckingReuse() {
            historyContains();

            assertThatThrownBy(() -> policy.validateForStaff(
                    STAFF_ID, encoder.encode("CurrentKham2027!"), "weak"))
                    .isInstanceOf(WeakPasswordException.class)
                    .hasMessageContaining("at least 12 characters");
        }

        @Test
        void toleratesStaffWithNoCurrentHash() {
            historyContains();

            assertThatCode(() -> policy.validateForStaff(STAFF_ID, null, "BrandNewKham2028!"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void generatedBootstrapPasswordsAlwaysSatisfyThePolicy() {
        // The seeder relies on this: a generated password must never be rejected
        // by the very policy it has to pass.
        for (int i = 0; i < 200; i++) {
            String generated = com.sclinic.bootstrap.InitialPasswordGenerator.generate();
            assertThatCode(() -> policy.validateStrength(generated))
                    .as("generated password #%d", i)
                    .doesNotThrowAnyException();
            assertThat(generated).hasSize(20);
        }
    }
}
