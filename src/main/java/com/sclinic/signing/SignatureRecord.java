package com.sclinic.signing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One signature over one record. Append-only.
 *
 * <p>Named {@code SignatureRecord} rather than {@code Signature} to keep it
 * distinct from {@link java.security.Signature}, which the verifier uses a few
 * lines away; two things called Signature in one package is an import mistake
 * waiting to happen in a place where mistakes are expensive.
 */
@Entity
@Table(name = "signature")
@Getter
@Setter
public class SignatureRecord {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /**
     * What was signed.
     *
     * <p>Generic rather than a foreign key to encounter: prescriptions and
     * invoices will be signed by the same mechanism, and a signature table per
     * signed thing would mean the verification logic existed once per table.
     */
    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "signer_staff_id", nullable = false)
    private UUID signerStaffId;

    @Column(name = "signed_hash", nullable = false)
    private String signedHash;

    @Column(name = "hash_algorithm", nullable = false)
    private String hashAlgorithm;

    @Column(name = "signature_value", nullable = false)
    private String signatureValue;

    @Column(name = "signature_algorithm", nullable = false)
    private String signatureAlgorithm;

    /**
     * The signer's certificate at the moment of signing.
     *
     * <p>Stored per signature, not looked up from the staff row. Certificates are
     * renewed and expire; a signature made under a certificate that has since
     * expired is still a valid signature made while it was good, and showing that
     * requires the certificate that was actually used. Pointing at the signer's
     * current certificate would make every historical signature unverifiable the
     * first time one was renewed.
     */
    @Column(name = "certificate_chain")
    private String certificateChain;

    @Column(name = "certificate_subject")
    private String certificateSubject;

    @Column(name = "certificate_serial")
    private String certificateSerial;

    @Column(name = "provider_code", nullable = false)
    private String providerCode;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
