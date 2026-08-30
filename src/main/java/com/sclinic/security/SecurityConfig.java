package com.sclinic.security;

import com.sclinic.security.session.SessionTokenAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration.
 *
 * <p>Authentication is by opaque server-side session token sent as
 * {@code Authorization: Bearer <token>}. HTTP Basic was removed: it puts the
 * password on every single request and cannot be revoked, which is unacceptable
 * for access to health records. See {@code SessionTokenService} for why the
 * token is opaque rather than a JWT.
 *
 * <p>CSRF stays disabled: there is no cookie-based session, so there is nothing
 * a browser would attach automatically. Revisit if cookies are ever introduced.
 *
 * <p>All {@code /api/**} endpoints require authentication except the login,
 * logout and change-password entry points, which run before a full session
 * exists and validate their own tokens. Fine-grained authorization is per
 * endpoint with {@code @PreAuthorize}.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionTokenAuthFilter sessionTokenAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/logout",
                                "/api/auth/change-password",
                                // MFA steps run before a full session exists and
                                // validate their own interim tokens.
                                "/api/auth/mfa/enroll",
                                "/api/auth/mfa/confirm",
                                "/api/auth/mfa/verify").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(sessionTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()));
        return http.build();
    }

    /**
     * Answers 401 rather than the container default, and sends no
     * {@code WWW-Authenticate} challenge so browsers do not pop up a Basic auth
     * dialog for an API that no longer speaks Basic.
     */
    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) ->
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
