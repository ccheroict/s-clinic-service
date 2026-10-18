package com.sclinic.catalogue;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads the reference catalogues from a CSV file.
 *
 * <p>These are national lists published by the Ministry of Health. They are not
 * typed in and they are not seeded in a migration: inventing clinical codes would
 * put invented codes into prescriptions.
 *
 * <p>Imports are matched on code and are idempotent. Re-importing the same file
 * reports every row as skipped and changes nothing, so an operator who is unsure
 * whether the import ran can simply run it again. A row whose details differ is
 * updated and counted separately, because that is the number worth looking at: a
 * catalogue update that quietly changes the strength of a drug already used in
 * prescriptions is the one to notice before it happens.
 *
 * <p>A row that cannot be read is rejected with its line number and the rest of
 * the file continues. Rejecting the whole file for one bad line means a
 * four-thousand-row catalogue cannot be loaded because of a stray comma.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogueImportService {

    private static final String ENTITY_TYPE = "catalogue";
    private static final char DELIMITER = ',';

    private final CatalogIcd10Repository icd10Repository;
    private final CatalogAllergenRepository allergenRepository;
    private final CatalogDrugRepository drugRepository;
    private final AuditService auditService;

    /**
     * Expected header: {@code code,name,name_en,chapter}
     */
    @Transactional
    public CatalogueImportReport importIcd10(InputStream csv) {
        return runImport("icd10", csv, 2, (fields, report) -> {
            String code = fields[0];
            Optional<CatalogIcd10> existing = icd10Repository.findByCode(code);

            CatalogIcd10 entry = existing.orElseGet(CatalogIcd10::new);
            String name = fields[1];
            String nameEn = field(fields, 2);
            String chapter = field(fields, 3);

            if (existing.isPresent()
                    && Objects.equals(entry.getName(), name)
                    && Objects.equals(entry.getNameEn(), nameEn)
                    && Objects.equals(entry.getChapter(), chapter)
                    && entry.isActive()) {
                return Outcome.SKIPPED;
            }

            entry.setCode(code);
            entry.setName(name);
            entry.setNameEn(nameEn);
            entry.setChapter(chapter);
            entry.setActive(true);
            icd10Repository.save(entry);

            return existing.isPresent() ? Outcome.UPDATED : Outcome.ADDED;
        });
    }

    /**
     * Expected header: {@code code,name,category,parent_code}
     */
    @Transactional
    public CatalogueImportReport importAllergens(InputStream csv) {
        return runImport("allergen", csv, 3, (fields, report) -> {
            String code = fields[0];
            String name = fields[1];
            String category = fields[2].toUpperCase();

            if (!List.of("DRUG", "FOOD", "ENVIRONMENT", "OTHER").contains(category)) {
                throw new IllegalArgumentException(
                        "category must be DRUG, FOOD, ENVIRONMENT or OTHER, got: " + fields[2]);
            }

            // Resolved by code so a file can reference a class defined on an
            // earlier line without the operator having to know internal ids.
            String parentCode = field(fields, 3);
            java.util.UUID parentId = null;
            if (parentCode != null) {
                parentId = allergenRepository.findByCode(parentCode)
                        .map(CatalogAllergen::getId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "parent_code not found: " + parentCode
                                        + " (define the class before its members)"));
            }

            Optional<CatalogAllergen> existing = allergenRepository.findByCode(code);
            CatalogAllergen entry = existing.orElseGet(CatalogAllergen::new);

            if (existing.isPresent()
                    && Objects.equals(entry.getName(), name)
                    && Objects.equals(entry.getCategory(), category)
                    && Objects.equals(entry.getParentId(), parentId)
                    && entry.isActive()) {
                return Outcome.SKIPPED;
            }

            entry.setCode(code);
            entry.setName(name);
            entry.setCategory(category);
            entry.setParentId(parentId);
            entry.setActive(true);
            allergenRepository.save(entry);

            return existing.isPresent() ? Outcome.UPDATED : Outcome.ADDED;
        });
    }

    /**
     * Expected header:
     * {@code code,national_code,inn_name,brand_name,strength,dosage_form,route,unit,control_class,allergen_codes}
     *
     * <p>{@code allergen_codes} is semicolon-separated, because the field
     * delimiter is a comma and a drug containing three substances is ordinary.
     */
    @Transactional
    public CatalogueImportReport importDrugs(InputStream csv) {
        return runImport("drug", csv, 3, (fields, report) -> {
            String code = fields[0];
            String nationalCode = field(fields, 1);
            String innName = fields[2];

            Optional<CatalogDrug> existing = drugRepository.findByCode(code);
            CatalogDrug entry = existing.orElseGet(CatalogDrug::new);

            String controlClass = field(fields, 8);
            if (controlClass != null
                    && !List.of("NARCOTIC", "PSYCHOTROPIC", "PRECURSOR").contains(controlClass)) {
                throw new IllegalArgumentException(
                        "control_class must be NARCOTIC, PSYCHOTROPIC or PRECURSOR, got: "
                                + controlClass);
            }

            entry.setCode(code);
            entry.setNationalCode(nationalCode);
            entry.setInnName(innName);
            entry.setBrandName(field(fields, 3));
            entry.setStrength(field(fields, 4));
            entry.setDosageForm(field(fields, 5));
            entry.setRoute(field(fields, 6));
            entry.setUnit(Optional.ofNullable(field(fields, 7)).orElse("vien"));
            entry.setControlClass(controlClass);
            entry.setActive(true);

            String allergenCodes = field(fields, 9);
            if (allergenCodes != null) {
                entry.getAllergens().clear();
                for (String allergenCode : allergenCodes.split(";")) {
                    String trimmed = allergenCode.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    entry.getAllergens().add(allergenRepository.findByCode(trimmed)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "allergen code not found: " + trimmed
                                            + " (import the allergen catalogue first)")));
                }
            }

            drugRepository.save(entry);
            return existing.isPresent() ? Outcome.UPDATED : Outcome.ADDED;
        });
    }

    // ---------- the shared mechanics ----------

    private enum Outcome { ADDED, UPDATED, SKIPPED }

    private interface RowHandler {
        Outcome handle(String[] fields, CatalogueImportReport.RejectedRow[] unused);
    }

    /**
     * Reads the file, applies the handler per row, and counts the outcomes.
     *
     * @param minimumFields how many columns a row must have to be worth trying
     */
    private CatalogueImportReport runImport(String catalogue, InputStream csv,
                                            int minimumFields, RowHandler handler) {
        int added = 0;
        int updated = 0;
        int skipped = 0;
        List<CatalogueImportReport.RejectedRow> rejected = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csv, StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;
            boolean headerSkipped = false;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) {
                    continue;
                }
                // The first non-blank line is the header. Detected by position
                // rather than by content, so a catalogue whose first entry
                // happens to be called "code" is not silently discarded.
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                try {
                    String[] fields = splitCsvLine(line);
                    if (fields.length < minimumFields || fields[0].isBlank()) {
                        rejected.add(new CatalogueImportReport.RejectedRow(lineNumber,
                                "expected at least " + minimumFields
                                        + " columns with a code in the first, got "
                                        + fields.length));
                        continue;
                    }

                    switch (handler.handle(fields, null)) {
                        case ADDED -> added++;
                        case UPDATED -> updated++;
                        case SKIPPED -> skipped++;
                    }
                } catch (RuntimeException e) {
                    // One bad row must not stop a four-thousand-row catalogue.
                    rejected.add(new CatalogueImportReport.RejectedRow(
                            lineNumber, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the catalogue file", e);
        }

        CatalogueImportReport report =
                new CatalogueImportReport(added, updated, skipped, rejected);

        auditService.record(AuditAction.IMPORT, ENTITY_TYPE, null,
                AuditDetail.builder()
                        .code("catalogue", catalogue)
                        .code("added", String.valueOf(added))
                        .code("updated", String.valueOf(updated))
                        .code("skipped", String.valueOf(skipped))
                        .code("rejected", String.valueOf(rejected.size()))
                        .build());

        log.warn("Catalogue import [{}]: {} added, {} updated, {} skipped, {} rejected",
                catalogue, added, updated, skipped, rejected.size());

        return report;
    }

    /**
     * Splits one CSV line, honouring double quotes.
     *
     * <p>Hand-written rather than pulling in a CSV library: the files are
     * published catalogues with a fixed shape, and the only thing that needs
     * handling beyond splitting on commas is a quoted field containing one —
     * which drug names do, in strengths like {@code "500mg, 125mg"}.
     */
    static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                // A doubled quote inside a quoted field is a literal quote.
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == DELIMITER && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());

        return fields.toArray(new String[0]);
    }

    /** Optional column: absent, empty and whitespace all mean "not given". */
    private static String field(String[] fields, int index) {
        if (index >= fields.length) {
            return null;
        }
        String value = fields[index].trim();
        return value.isEmpty() ? null : value;
    }
}
