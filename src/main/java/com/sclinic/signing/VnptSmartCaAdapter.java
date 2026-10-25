package com.sclinic.signing;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Remote signing through VNPT SmartCA.
 *
 * <p><b>Read this before relying on it.</b> The call sequence below is modelled
 * on the Cloud Signature Consortium remote-signature API, which is the European
 * standard VNPT describes SmartCA as following, and on the shape such services
 * share: authenticate, locate the signer's credential, submit a hash, then poll
 * while the signer approves on their phone.
 *
 * <p>It has <b>not</b> been verified against VNPT's own integration
 * documentation, because that documentation is issued to contracted customers
 * and is not public. The endpoint paths, field names and error codes here are
 * therefore the part most likely to be wrong, and they are kept in one place —
 * the constants and the three private methods below — so that correcting them
 * against the real specification does not touch anything else.
 *
 * <p>What is <em>not</em> provisional is everything outside this class: the port,
 * the signature table, what gets hashed, and when a record becomes SIGNED. Those
 * follow from requirement 14 rather than from any provider, which is the reason
 * the port exists.
 *
 * <p>Enabled only by {@code sclinic.signing.provider=VNPT_SMARTCA}. Until a
 * clinic sets that and supplies credentials,
 * {@link UnconfiguredSignatureProvider} refuses to sign rather than pretending.
 */
@Component
@ConditionalOnProperty(name = "sclinic.signing.provider", havingValue = "VNPT_SMARTCA")
@Slf4j
public class VnptSmartCaAdapter implements DigitalSignatureProvider {

    static final String PROVIDER_CODE = "VNPT_SMARTCA";

    /*
     * Endpoint paths, isolated so they can be corrected in one place once the
     * real specification is to hand. Named after the CSC operations they
     * correspond to.
     */
    private static final String PATH_CREDENTIAL_INFO = "/credentials/info";
    private static final String PATH_SIGN_HASH = "/signatures/signHash";
    private static final String PATH_TRANSACTION_STATUS = "/signatures/status";

    private final SigningProperties properties;
    private final RestClient restClient;
    private final java.time.Clock clock;

    public VnptSmartCaAdapter(SigningProperties properties, java.time.Clock clock) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            // Fails at startup rather than at the first signature. A clinic
            // discovering this when a doctor tries to sign their first record is
            // a clinic discovering it in front of a patient.
            throw new IllegalStateException(
                    "sclinic.signing.provider is VNPT_SMARTCA but sclinic.signing.base-url "
                            + "is not set.");
        }
        if (properties.clientId() == null || properties.clientSecret() == null) {
            throw new IllegalStateException(
                    "sclinic.signing.provider is VNPT_SMARTCA but client-id / client-secret "
                            + "are not set.");
        }

        this.properties = properties;
        this.clock = clock;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    /**
     * Submits the hash and waits for the signer to approve on their phone.
     *
     * <p>Blocking, and polling rather than waiting on a callback. A callback would
     * need an endpoint the provider can reach, which means exposing one to the
     * internet from inside a clinic network — a larger decision than this feature
     * should make on the clinic's behalf. Polling costs a held request for as
     * long as the doctor takes to find their phone, which for a few signatures a
     * day is affordable.
     */
    @Override
    public SignatureResult sign(SignatureRequest request) {
        String transactionId = submitHash(request);

        Instant deadline = clock.instant().plus(properties.confirmationTimeout());

        while (clock.instant().isBefore(deadline)) {
            JsonNode status = transactionStatus(transactionId);
            String state = status.path("status").asText("");

            if ("SIGNED".equalsIgnoreCase(state) || "COMPLETED".equalsIgnoreCase(state)) {
                return toResult(status, transactionId);
            }
            if ("REJECTED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                // The signer said no. A refusal, not an error: the record stays a
                // draft and the doctor knows why.
                throw new SignatureFailedException(
                        "Người ký đã từ chối yêu cầu ký trên ứng dụng SmartCA.");
            }

            sleep(properties.pollInterval());
        }

        throw new SignatureFailedException(
                "Hết thời gian chờ xác nhận ký trên ứng dụng SmartCA ("
                        + properties.confirmationTimeout().toMinutes() + " phút). "
                        + "Bản ghi vẫn ở trạng thái nháp.");
    }

    /**
     * Verification is local.
     *
     * <p>Checking a signature needs the signature, the certificate and the hash,
     * all of which are stored on the row — so this does not call the provider at
     * all. That is the point: a record signed five years ago must stay verifiable
     * after the clinic has changed provider, or after the provider has stopped
     * answering, and a verification that depends on a third party being reachable
     * is a verification that fails when it is most needed.
     */
    @Override
    public SignatureVerification verify(SignatureVerificationRequest request) {
        return LocalSignatureVerifier.verify(request);
    }

    // ---------- provider-shaped calls, the provisional part ----------

    private String submitHash(SignatureRequest request) {
        try {
            JsonNode response = restClient.post()
                    .uri(PATH_SIGN_HASH)
                    .headers(this::authorise)
                    .body(Map.of(
                            "credentialID", request.credentialId(),
                            "hashAlgo", request.hashAlgorithm(),
                            "hash", new String[]{request.hashBase64()},
                            // Shown to the signer on their phone. Must not name
                            // the patient: it is read on a lock screen, often in
                            // public.
                            "description", request.displayName()))
                    .retrieve()
                    .body(JsonNode.class);

            String transactionId = response == null ? null
                    : response.path("transactionId").asText(null);

            if (transactionId == null || transactionId.isBlank()) {
                throw new SignatureFailedException(
                        "SmartCA không trả về mã giao dịch ký.");
            }
            return transactionId;
        } catch (SignatureFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            // The provider's message is not passed through. It may name internal
            // systems, and a clinician cannot act on it either way.
            log.error("SmartCA signHash call failed", e);
            throw new SignatureFailedException(
                    "Không gửi được yêu cầu ký tới SmartCA. Hãy thử lại; "
                            + "bản ghi vẫn ở trạng thái nháp.", e);
        }
    }

    private JsonNode transactionStatus(String transactionId) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(PATH_TRANSACTION_STATUS)
                            .queryParam("transactionId", transactionId)
                            .build())
                    .headers(this::authorise)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            log.error("SmartCA status call failed for transaction {}", transactionId, e);
            throw new SignatureFailedException(
                    "Mất liên lạc với SmartCA trong khi chờ xác nhận ký.", e);
        }
    }

    private SignatureResult toResult(JsonNode status, String transactionId) {
        String signature = status.path("signatures").path(0).asText(null);
        if (signature == null || signature.isBlank()) {
            signature = status.path("signature").asText(null);
        }
        if (signature == null || signature.isBlank()) {
            throw new SignatureFailedException(
                    "SmartCA báo đã ký nhưng không trả về giá trị chữ ký.");
        }

        return new SignatureResult(
                signature,
                status.path("signatureAlgo").asText("SHA256withRSA"),
                status.path("certificate").asText(null),
                status.path("certificateSubject").asText(null),
                status.path("certificateSerial").asText(null),
                // The provider's timestamp where it gives one. Theirs rather than
                // the clinic's clock, because a dispute would turn on the moment
                // the signing service says it signed.
                status.hasNonNull("signedAt")
                        ? Instant.parse(status.get("signedAt").asText())
                        : clock.instant(),
                transactionId);
    }

    private void authorise(HttpHeaders headers) {
        // Placeholder for the provider's own scheme, which the integration
        // documentation defines. Isolated here so that replacing it with an
        // OAuth token exchange, which is what the CSC standard describes, touches
        // nothing else.
        headers.setBasicAuth(properties.clientId(), properties.clientSecret());
    }

    private void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignatureFailedException("Bị ngắt trong khi chờ xác nhận ký.", e);
        }
    }
}
