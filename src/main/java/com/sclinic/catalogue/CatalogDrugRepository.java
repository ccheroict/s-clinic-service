package com.sclinic.catalogue;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogDrugRepository extends JpaRepository<CatalogDrug, UUID> {

    Optional<CatalogDrug> findByCode(String code);

    Optional<CatalogDrug> findByNationalCode(String nationalCode);

    /**
     * One drug with its substances already loaded.
     *
     * <p>{@code left join fetch} rather than relying on the session still being
     * open: the allergens are a lazy collection, and anything that reads them
     * after the transaction ends fails. Joining here moves that from a runtime
     * accident to a decision at the call site.
     */
    @Query("""
            SELECT d FROM CatalogDrug d
              LEFT JOIN FETCH d.allergens
             WHERE d.code = :code
            """)
    Optional<CatalogDrug> findDetailByCode(@Param("code") String code);

    /**
     * Matches the clinic code, the national code, the substance name and the
     * brand name.
     *
     * <p>A clinician looking for Augmentin types Augmentin; the one looking for
     * amoxicillin types that. Both have to find the same drug, which is why the
     * brand name is searched alongside the INN rather than instead of it.
     */
    @Query("""
            SELECT d FROM CatalogDrug d
             WHERE d.active = true
               AND (LOWER(d.code) LIKE LOWER(CONCAT(:term, '%'))
                    OR LOWER(COALESCE(d.nationalCode, '')) LIKE LOWER(CONCAT(:term, '%'))
                    OR LOWER(d.innName) LIKE LOWER(CONCAT('%', :term, '%'))
                    OR LOWER(COALESCE(d.brandName, '')) LIKE LOWER(CONCAT('%', :term, '%')))
             ORDER BY d.innName
            """)
    List<CatalogDrug> suggest(@Param("term") String term, Limit limit);

    /**
     * Drugs containing any of these substances.
     *
     * <p>The query behind the allergy check at prescribing time: given what a
     * patient reacts to, which drugs must not be prescribed.
     */
    @Query("""
            SELECT DISTINCT d FROM CatalogDrug d
              JOIN d.allergens a
             WHERE a.id IN :allergenIds
            """)
    List<CatalogDrug> findContainingAnyAllergen(
            @Param("allergenIds") Collection<UUID> allergenIds);
}
