package com.sclinic.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Carries existing plaintext identifiers into the encrypted columns added by V9.
 *
 * <p>Runs at startup and is idempotent: it only touches rows where a plaintext
 * value exists and the encrypted one does not, so restarting is harmless and a
 * partially completed run simply finishes next time. It reads the plaintext
 * columns through plain SQL rather than through the entity, because the entity is
 * already mapped to the encrypted columns.
 *
 * <p>This exists for deployments that already hold patient data. On a fresh
 * database it finds nothing and logs nothing.
 *
 * <p>Deliberately not deleting the plaintext afterwards. Dropping those columns
 * is migration V10 in a later release, which is the window in which a conversion
 * that went wrong can still be diagnosed against the original values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentifierEncryptionJob implements CommandLineRunner {

    /** Bounded so a large table is converted in steady chunks, not one transaction. */
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final AesGcmCipher cipher;
    private final BlindIndex blindIndex;

    @Override
    public void run(String... args) {
        long converted = 0;
        int inBatch;

        do {
            inBatch = convertBatch();
            converted += inBatch;
        } while (inBatch > 0);

        if (converted > 0) {
            log.warn("""

                    ============================================================
                    Encrypted the identity numbers of {} patient record(s).
                    The plaintext columns are still present on purpose; verify
                    with the query at the end of V9__encrypt_identifiers.sql,
                    then deploy the release carrying V10 to drop them.
                    ============================================================""", converted);
        }
    }

    @Transactional
    int convertBatch() {
        List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                SELECT id, national_id, insurance_no, tax_code
                  FROM patient
                 WHERE (national_id  IS NOT NULL AND national_id_enc  IS NULL)
                    OR (insurance_no IS NOT NULL AND insurance_no_enc IS NULL)
                    OR (tax_code     IS NOT NULL AND tax_code_enc     IS NULL)
                 LIMIT ?
                """, BATCH_SIZE);

        for (Map<String, Object> row : pending) {
            String nationalId = (String) row.get("national_id");

            jdbcTemplate.update("""
                    UPDATE patient
                       SET national_id_enc  = COALESCE(national_id_enc, ?),
                           insurance_no_enc = COALESCE(insurance_no_enc, ?),
                           tax_code_enc     = COALESCE(tax_code_enc, ?),
                           national_id_hash = COALESCE(national_id_hash, ?)
                     WHERE id = ?
                    """,
                    cipher.encrypt(nationalId),
                    cipher.encrypt((String) row.get("insurance_no")),
                    cipher.encrypt((String) row.get("tax_code")),
                    blindIndex.of(nationalId),
                    row.get("id"));
        }

        return pending.size();
    }
}
