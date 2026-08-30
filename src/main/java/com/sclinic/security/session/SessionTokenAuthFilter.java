package com.sclinic.security.session;

import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests carrying a session token.
 *
 * <p>Only {@link TokenScope#FULL} tokens produce an {@code Authentication}.
 * Interim tokens (change-password, MFA steps) are intentionally left
 * unauthenticated so that a half-finished login cannot reach a business
 * endpoint even if a rule is later misconfigured; the auth endpoints read those
 * tokens directly instead.
 */
@Component
@RequiredArgsConstructor
public class SessionTokenAuthFilter extends OncePerRequestFilter {

    private final SessionTokenService sessionTokenService;
    private final StaffRepository staffRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request) {
        String rawToken = BearerTokenReader.read(request);
        if (rawToken == null) {
            return;
        }

        Optional<SessionToken> token = sessionTokenService.touch(rawToken)
                .filter(t -> t.getScope() == TokenScope.FULL);
        if (token.isEmpty()) {
            return;
        }

        Optional<Staff> staff = staffRepository.findById(token.get().getStaffId())
                .filter(Staff::isActive);
        if (staff.isEmpty()) {
            // Deactivated between issuing the token and using it.
            return;
        }

        Staff principal = staff.get();
        var authentication = new UsernamePasswordAuthenticationToken(
                principal.getUsername(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.getRole())));
        authentication.setDetails(token.get().getId());

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
