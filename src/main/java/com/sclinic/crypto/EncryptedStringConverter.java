package com.sclinic.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Encrypts a string column on the way to the database and decrypts it on the way
 * back.
 *
 * <p>A JPA converter rather than {@code pgcrypto}: with pgcrypto the key travels
 * in the SQL statement, which puts it in the statement log, in
 * {@code pg_stat_statements}, and in the slow-query log of whatever sits in front
 * of the database. Doing it in the application means the key never leaves the JVM,
 * and a database backup is useless without it.
 *
 * <p>Not applied automatically. Each field says so with {@code @Convert}, because
 * which columns hold identifying data is a decision worth seeing at the field.
 */
@Converter
@Component
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final AesGcmCipher cipher;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    /**
     * Tolerates values written before the column was encrypted, so the
     * application keeps working between the migration that adds the encrypted
     * column and the conversion that fills it.
     *
     * <p>Only in that direction. Anything written from here on is encrypted; this
     * is about reading what is already there, not a way to opt out.
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!AesGcmCipher.isEncrypted(dbData)) {
            return dbData;
        }
        return cipher.decrypt(dbData);
    }
}
