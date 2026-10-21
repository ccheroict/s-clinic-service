package com.sclinic.catalogue;

import com.sclinic.catalogue.dto.CatalogueResponses;
import com.sclinic.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Reading and loading the reference catalogues.
 *
 * <p>Suggestions are open to any authenticated member of staff: a receptionist
 * booking a service and a doctor picking a diagnosis both need them, and a drug
 * name is not patient data. Loading a catalogue is admin only, because it changes
 * what can be prescribed clinic-wide.
 */
@RestController
@RequestMapping("/api/catalogues")
@RequiredArgsConstructor
public class CatalogueController {

    /**
     * Suggestion lists are for a type-ahead, so they are capped. Returning four
     * thousand ICD-10 codes to a picker is slower than typing one more letter.
     */
    private static final int SUGGESTION_LIMIT = 20;

    /**
     * One character matches most of the catalogue and tells the user nothing, so
     * the shortest useful prefix is two.
     */
    private static final int MINIMUM_TERM_LENGTH = 2;

    private final CatalogIcd10Repository icd10Repository;
    private final CatalogAllergenRepository allergenRepository;
    private final CatalogDrugRepository drugRepository;
    private final CatalogueImportService importService;

    @GetMapping("/icd10")
    public List<CatalogueResponses.Icd10> suggestDiagnoses(@RequestParam String q) {
        return tooShort(q) ? List.of()
                : icd10Repository.suggest(q.trim(), Limit.of(SUGGESTION_LIMIT))
                        .stream().map(CatalogueResponses.Icd10::from).toList();
    }

    @GetMapping("/allergens")
    public List<CatalogueResponses.Allergen> suggestAllergens(@RequestParam String q) {
        return tooShort(q) ? List.of()
                : allergenRepository.suggest(q.trim(), Limit.of(SUGGESTION_LIMIT))
                        .stream().map(CatalogueResponses.Allergen::from).toList();
    }

    @GetMapping("/drugs")
    public List<CatalogueResponses.DrugSummary> suggestDrugs(@RequestParam String q) {
        return tooShort(q) ? List.of()
                : drugRepository.suggest(q.trim(), Limit.of(SUGGESTION_LIMIT))
                        .stream().map(CatalogueResponses.DrugSummary::from).toList();
    }

    /**
     * Resolves one drug by code, with the substances it contains.
     *
     * <p>Inactive entries resolve too, on purpose: a prescription written last
     * year references a drug that may since have been withdrawn, and it still has
     * to render.
     *
     * <p>Transactional because the allergens are fetched with the drug and turned
     * into a response while the session is open. See {@code CatalogueResponses}.
     */
    @GetMapping("/drugs/{code}")
    @Transactional(readOnly = true)
    public CatalogueResponses.DrugDetail drugByCode(@PathVariable String code) {
        return drugRepository.findDetailByCode(code)
                .map(CatalogueResponses.DrugDetail::from)
                .orElseThrow(() -> new com.sclinic.common.exception.NotFoundException(
                        "Drug not found: " + code));
    }

    @PostMapping(path = "/icd10/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogueImportReport importDiagnoses(@RequestPart("file") MultipartFile file) {
        return importService.importIcd10(read(file));
    }

    @PostMapping(path = "/allergens/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogueImportReport importAllergens(@RequestPart("file") MultipartFile file) {
        return importService.importAllergens(read(file));
    }

    /**
     * Drugs reference allergens by code, so the allergen catalogue has to be
     * loaded first. A drug row naming an unknown allergen is rejected with that
     * message rather than being imported without its allergen links, which would
     * leave the allergy check silently incomplete.
     */
    @PostMapping(path = "/drugs/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogueImportReport importDrugs(@RequestPart("file") MultipartFile file) {
        return importService.importDrugs(read(file));
    }

    private boolean tooShort(String term) {
        return term == null || term.trim().length() < MINIMUM_TERM_LENGTH;
    }

    private java.io.InputStream read(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("The uploaded file is empty");
        }
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded file", e);
        }
    }
}
