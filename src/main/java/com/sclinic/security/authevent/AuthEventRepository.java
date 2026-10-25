package com.sclinic.security.authevent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuthEventRepository extends JpaRepository<AuthEvent, Long> {

    List<AuthEvent> findByUsernameOrderByCreatedAtDesc(String username);

    List<AuthEvent> findByStaffIdOrderByCreatedAtDesc(UUID staffId);

    List<AuthEvent> findByEventTypeOrderByCreatedAtDesc(String eventType);

    /**
     * Removes authentication events older than a cut-off.
     *
     * <p>Only ever called when an operator has configured a retention period.
     * This table is the record of who tried to log in and when, so trimming it
     * removes evidence — and the evidence is usually wanted long after the point
     * at which anyone thought to ask for it.
     */
    @Modifying
    @Query("delete from AuthEvent e where e.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);
}
