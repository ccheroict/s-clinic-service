package com.sclinic.signing;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

/**
 * A minimal self-signed X.509 certificate, built by hand for tests.
 *
 * <p>Hand-rolled rather than pulled in with Bouncy Castle. The only thing the
 * verifier reads from a certificate is the public key, so what is needed is a
 * public key in X.509 clothing — and adding a cryptography library to the
 * production dependency tree to produce test fixtures would be paying for it
 * everywhere to use it in one place.
 *
 * <p>This is <b>test scaffolding only</b>. It is in the test sources on purpose:
 * nothing shippable can reach it, which matters because a class that mints
 * certificates is exactly what should not be available to a signing path whose
 * whole value rests on the key living somewhere else.
 */
final class TestCertificates {

    private TestCertificates() {
    }

    static String selfSigned(KeyPair keyPair) throws Exception {
        byte[] tbs = tbsCertificate(keyPair);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] signatureValue = signer.sign();

        byte[] certificate = sequence(
                tbs,
                sha256WithRsaAlgorithmId(),
                bitString(signatureValue));

        return Base64.getEncoder().encodeToString(certificate);
    }

    // ---------- DER, only as much as a certificate needs ----------

    private static byte[] tbsCertificate(KeyPair keyPair) throws Exception {
        return sequence(
                // version [0] EXPLICIT v3
                explicit(0, integer(BigInteger.valueOf(2))),
                integer(BigInteger.ONE),                   // serialNumber
                sha256WithRsaAlgorithmId(),                // signature
                name("S-Clinic Test"),                     // issuer
                validity(),
                name("S-Clinic Test"),                     // subject
                keyPair.getPublic().getEncoded());         // SubjectPublicKeyInfo
    }

    private static byte[] validity() throws Exception {
        // Fixed, wide window. These tests are about whether a signature verifies,
        // not about certificate lifetimes, and a window tied to the clock would
        // make them fail on a machine whose date is wrong for unrelated reasons.
        return sequence(
                utcTime("200101000000Z"),
                utcTime("491231235959Z"));
    }

    private static byte[] name(String commonName) throws Exception {
        byte[] cnOid = {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] cnValue = utf8String(commonName);

        return sequence(set(sequence(cnOid, cnValue)));
    }

    private static byte[] sha256WithRsaAlgorithmId() throws Exception {
        byte[] oid = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
        byte[] nullParams = {0x05, 0x00};
        return sequence(oid, nullParams);
    }

    private static byte[] sequence(byte[]... parts) throws Exception {
        return tagged(0x30, concat(parts));
    }

    private static byte[] set(byte[] content) throws Exception {
        return tagged(0x31, content);
    }

    private static byte[] explicit(int tagNumber, byte[] content) throws Exception {
        return tagged(0xA0 | tagNumber, content);
    }

    private static byte[] integer(BigInteger value) throws Exception {
        return tagged(0x02, value.toByteArray());
    }

    private static byte[] bitString(byte[] value) throws Exception {
        byte[] withPadding = new byte[value.length + 1];
        withPadding[0] = 0; // no unused bits
        System.arraycopy(value, 0, withPadding, 1, value.length);
        return tagged(0x03, withPadding);
    }

    private static byte[] utf8String(String value) throws Exception {
        return tagged(0x0C, value.getBytes("UTF-8"));
    }

    private static byte[] utcTime(String value) throws Exception {
        return tagged(0x17, value.getBytes("US-ASCII"));
    }

    private static byte[] tagged(int tag, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }

        int byteCount = 0;
        for (int remaining = length; remaining > 0; remaining >>= 8) {
            byteCount++;
        }
        out.write(0x80 | byteCount);
        for (int i = byteCount - 1; i >= 0; i--) {
            out.write((length >> (i * 8)) & 0xFF);
        }
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }
}
