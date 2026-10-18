package com.sclinic.catalogue;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogAllergenRepository extends JpaRepository<CatalogAllergen, UUID> {

    Optional<CatalogAllergen> findByCode(String code);

    /** See {@link CatalogIcd10Repository#suggest} for why both fields are matched. */
    @Query("""
            SELECT a FROM CatalogAllergen a
             WHERE a.active = true
               AND (LOWER(a.code) LIKE LOWER(CONCAT(:term, '%'))
                    OR LOWER(a.name) LIKE LOWER(CONCAT('%', :term, '%')))
             ORDER BY a.name
            """)
    List<CatalogAllergen> suggest(@Param("term") String term, Limit limit);

    /** Members of a class, so an allergy to the class can warn about each of them. */
    List<CatalogAllergen> findByParentIdAndActiveTrue(UUID parentId);
}
