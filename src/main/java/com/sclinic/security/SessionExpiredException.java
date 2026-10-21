package com.sclinic.security;

/**
 * The token presented was real and has run out, or been revoked.
 *
 * <p>Separate from {@link AuthenticationFailedException} so that a half-finished
 * login which timed out does not read as a wrong code. An MFA token lives ten
 * minutes; someone who leaves the code screen open over a break returns to a
 * dead token, and being told "invalid authentication code" sends them to re-read
 * a code that was never the problem and eventually to conclude the authenticator
 * app is broken. What they need to be told is to sign in again.
 *
 * <p>Still a 401, and still says nothing about any account. The distinction is
 * about a token the caller already holds, not about whether some username
 * exists, so it adds nothing to what an attacker can learn — unlike the login
 * failures, which are deliberately uniform.
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException() {
        super("Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.");
    }
}
