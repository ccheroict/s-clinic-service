package com.sclinic.common.exception;

/**
 * A request the client got wrong, with a message meant for the client to read.
 *
 * <p>Exists so that {@link IllegalArgumentException} can stop meaning this.
 * Every {@code IllegalArgumentException} used to be mapped to 400 with its
 * message passed straight through, which is right for a deliberate rejection
 * like "that date is in the future" and wrong for everything else: Java throws
 * it for broken internal invariants too, and those became 400 responses telling
 * a client about the clinic's internals. {@code Base32.decode} rejecting a
 * corrupted stored secret is a server fault, not a client mistake, and
 * "Not a base32 character: ?" is not something a receptionist can act on.
 *
 * <p>So the rule is now explicit: throw this when the caller can fix it, and let
 * anything else be a 500. Choosing to answer 400 is a decision worth making in
 * so many words, rather than a side effect of which exception type happened to
 * be nearest.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    /**
     * For a rejection caused by something lower down, where the cause belongs in
     * the log and the message belongs to the caller — a truncated upload, say.
     */
    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
