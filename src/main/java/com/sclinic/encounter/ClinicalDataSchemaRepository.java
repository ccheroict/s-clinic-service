package com.sclinic.encounter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClinicalDataSchemaRepository extends JpaRepository<ClinicalDataSchema, UUID> {

    /**
     * The form currently in force for a specialty: highest published version.
     *
     * <p>New records are written against this. Old records are read against
     * whichever version their row names, which is why this is not the only way in
     * to the table.
     */
    @Query("""
            select s from ClinicalDataSchema s
            where s.specialtyCode = :specialtyCode
              and s.publishedAt is not null
              and s.retiredAt is null
            order by s.version desc
            limit 1
            """)
    Optional<ClinicalDataSchema> findCurrent(@Param("specialtyCode") String specialtyCode);

    List<ClinicalDataSchema> findBySpecialtyCodeOrderByVersionDesc(String specialtyCode);

    @Query("select coalesce(max(s.version), 0) from ClinicalDataSchema s "
            + "where s.specialtyCode = :specialtyCode")
    int highestVersion(@Param("specialtyCode") String specialtyCode);
}
