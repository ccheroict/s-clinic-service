package com.sclinic.security.password;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StaffPasswordHistoryRepository extends JpaRepository<StaffPasswordHistory, Long> {

    List<StaffPasswordHistory> findByStaffIdOrderByCreatedAtDesc(UUID staffId, Limit limit);

    List<StaffPasswordHistory> findByStaffIdOrderByCreatedAtDesc(UUID staffId);
}
