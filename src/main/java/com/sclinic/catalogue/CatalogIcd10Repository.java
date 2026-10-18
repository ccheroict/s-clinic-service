package com.sclinic.catalogue;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogIcd10Repository extends JpaRepository<CatalogIcd10, UUID> {

    /**
     * Resolvable even when inactive: a prescription written last year references
     * a code that may since have been retired, and it still has to render.
     */
    Optional<CatalogIcd10> findByCode(String code);

    /**
     * Suggestions as the user types, matching the code <b>and</b> the name.
     *
     * <p>Clinicians who know a code type the code; everyone else types what the
     * thing is called. A picker that searches only one of them is a picker half
     * the staff cannot use.
     *
     * <p>Codes match from the start, names anywhere: someone typing "L20" means a
     * code beginning with it, while someone typing "viem" means a name containing
     * it.
     */
    @Query("""
            SELECT d FROM CatalogIcd10 d
             WHERE d.active = true
               AND (LOWER(d.code) LIKE LOWER(CONCAT(:term, '%'))
                    OR LOWER(d.name) LIKE LOWER(CONCAT('%', :term, '%'))
                    OR LOWER(COALESCE(d.nameEn, '')) LIKE LOWER(CONCAT('%', :term, '%')))
             ORDER BY d.code
            """)
    List<CatalogIcd10> suggest(@Param("term") String term, Limit limit);

    List<CatalogIcd10> findByChapterAndActiveTrueOrderByCode(String chapter);
}
