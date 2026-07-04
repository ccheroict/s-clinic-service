package com.sclinic.security;

import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads authentication principals from the {@code staff} table.
 * Authorities are derived from the staff role as ROLE_DOCTOR / ROLE_RECEPTIONIST / ROLE_ADMIN.
 */
@Service
@RequiredArgsConstructor
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffRepository staffRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        Staff staff = staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        return new User(
                staff.getUsername(),
                staff.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + staff.getRole()))
        );
    }
}
