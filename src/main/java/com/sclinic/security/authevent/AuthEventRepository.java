package com.sclinic.security.authevent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuthEventRepository extends JpaRepository<AuthEvent, Long> {

    List<AuthEvent> findByUsernameOrderByCreatedAtDesc(String username);

    List<AuthEvent> findByStaffIdOrderByCreatedAtDesc(UUID staffId);

    List<AuthEvent> findByEventTypeOrderByCreatedAtDesc(String eventType);
}
