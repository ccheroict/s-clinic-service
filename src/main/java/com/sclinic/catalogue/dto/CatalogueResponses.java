package com.sclinic.catalogue.dto;

import com.sclinic.catalogue.CatalogAllergen;
import com.sclinic.catalogue.CatalogDrug;
import com.sclinic.catalogue.CatalogIcd10;

import java.util.List;
import java.util.UUID;

/**
 * What the catalogue endpoints return.
 *
 * <p>DTOs rather than the entities themselves, for a reason that is not stylistic:
 * {@code CatalogDrug.allergens} is a lazy collection, and serialising the entity
 * outside a transaction fails at the point Jackson reaches it. With
 * {@code open-in-view} disabled — which is the right setting — that is every
 * response. Returning a record built inside the transaction makes the boundary
 * explicit instead of leaving it to whether a session happens to still be open.
 *
 * <p>It also lets a suggestion list avoid loading the allergens at all: a picker
 * shows a name and a strength, and fetching the substances for twenty drugs to
 * display none of them is twenty queries for nothing.
 */
public final class CatalogueResponses {

    private CatalogueResponses() {
    }

    /** A diagnosis code, as shown in a picker. */
    public record Icd10(
            UUID id,
            String code,
            String name,
            String nameEn,
            String chapter
    ) {
        public static Icd10 from(CatalogIcd10 entry) {
            return new Icd10(entry.getId(), entry.getCode(), entry.getName(),
                    entry.getNameEn(), entry.getChapter());
        }
    }

    /** A substance a patient can react to. */
    public record Allergen(
            UUID id,
            String code,
            String name,
            String category,
            UUID parentId
    ) {
        public static Allergen from(CatalogAllergen entry) {
            return new Allergen(entry.getId(), entry.getCode(), entry.getName(),
                    entry.getCategory(), entry.getParentId());
        }
    }

    /**
     * A drug as shown in a picker. No allergens: the list does not display them,
     * and loading them for every row would be a query per row for nothing.
     */
    public record DrugSummary(
            UUID id,
            String code,
            String nationalCode,
            String innName,
            String brandName,
            String strength,
            String dosageForm,
            String route,
            String unit,
            String controlClass,
            boolean specialControl
    ) {
        public static DrugSummary from(CatalogDrug drug) {
            return new DrugSummary(drug.getId(), drug.getCode(), drug.getNationalCode(),
                    drug.getInnName(), drug.getBrandName(), drug.getStrength(),
                    drug.getDosageForm(), drug.getRoute(), drug.getUnit(),
                    drug.getControlClass(), drug.isSpecialControl());
        }
    }

    /**
     * One drug in full, including what it contains.
     *
     * <p>Built from an entity fetched with its allergens joined, so the collection
     * is already loaded by the time this is constructed.
     */
    public record DrugDetail(
            DrugSummary drug,
            List<Allergen> allergens
    ) {
        public static DrugDetail from(CatalogDrug drug) {
            return new DrugDetail(
                    DrugSummary.from(drug),
                    drug.getAllergens().stream().map(Allergen::from).toList());
        }
    }
}
