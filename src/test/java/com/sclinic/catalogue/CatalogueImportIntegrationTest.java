package com.sclinic.catalogue;

import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loading the reference catalogues, against a real database.
 *
 * <p>The behaviour that matters for an operator is the report: whether a given
 * import added entries, changed existing ones, or did nothing. "Imported 4,000
 * rows" answers the wrong question — a catalogue update that silently rewrites
 * the strength of a drug already used in prescriptions is the one worth noticing.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-CAT-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
class CatalogueImportIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("catalogue_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    CatalogueImportService importService;

    @Autowired
    CatalogIcd10Repository icd10Repository;

    @Autowired
    CatalogAllergenRepository allergenRepository;

    @Autowired
    CatalogDrugRepository drugRepository;

    /**
     * {@code deleteAllInBatch} rather than {@code deleteAll}: the allergen table
     * references itself, and per-row deletes fail on the parent while its
     * children are still there. One {@code delete from} statement is checked at
     * statement end, so the whole set goes together.
     */
    @BeforeEach
    void emptyTheCatalogues() {
        // Drugs first: the join table references allergens.
        drugRepository.deleteAllInBatch();
        allergenRepository.deleteAllInBatch();
        icd10Repository.deleteAllInBatch();
    }

    private java.io.InputStream csv(String... lines) {
        return new ByteArrayInputStream(
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- the report ----------

    @Nested
    class TheReport {

        @Test
        void countsNewEntriesAsAdded() {
            CatalogueImportReport report = importService.importIcd10(csv(
                    "code,name,name_en,chapter",
                    "L20.9,Viem da co dia,Atopic dermatitis,XII",
                    "L40,Vay nen,Psoriasis,XII"));

            assertThat(report.added()).isEqualTo(2);
            assertThat(report.updated()).isZero();
            assertThat(report.skipped()).isZero();
            assertThat(report.rejected()).isEmpty();
        }

        /**
         * Re-running the same file changes nothing and says so. An operator who
         * is unsure whether the import already ran can simply run it again.
         */
        @Test
        void countsUnchangedEntriesAsSkipped() {
            String[] file = {
                    "code,name,name_en,chapter",
                    "L20.9,Viem da co dia,Atopic dermatitis,XII"};
            importService.importIcd10(csv(file));

            CatalogueImportReport second = importService.importIcd10(csv(file));

            assertThat(second.added()).isZero();
            assertThat(second.skipped()).isEqualTo(1);
            assertThat(second.updated()).isZero();
        }

        /**
         * The number that matters. A changed row is reported separately from a
         * new one, because changing an entry already referenced by prescriptions
         * is the event worth reviewing.
         */
        @Test
        void countsChangedEntriesAsUpdated() {
            importService.importIcd10(csv(
                    "code,name,name_en,chapter",
                    "L20.9,Ten cu,Atopic dermatitis,XII"));

            CatalogueImportReport second = importService.importIcd10(csv(
                    "code,name,name_en,chapter",
                    "L20.9,Ten moi,Atopic dermatitis,XII"));

            assertThat(second.updated()).isEqualTo(1);
            assertThat(second.added()).isZero();
            assertThat(icd10Repository.findByCode("L20.9").orElseThrow().getName())
                    .isEqualTo("Ten moi");
        }

        @Test
        void totalsEveryRowItSaw() {
            CatalogueImportReport report = importService.importIcd10(csv(
                    "code,name",
                    "L20.9,Viem da co dia",
                    "L40,Vay nen",
                    ",Thieu ma"));

            assertThat(report.total()).isEqualTo(3);
            assertThat(report.added()).isEqualTo(2);
            assertThat(report.rejected()).hasSize(1);
        }
    }

    // ---------- bad rows ----------

    @Nested
    class RowsThatCannotBeRead {

        /**
         * One bad line must not stop the file. Rejecting a four-thousand-row
         * catalogue because of a stray comma means the catalogue never loads.
         */
        @Test
        void areRejectedIndividuallyWhileTheRestLoads() {
            CatalogueImportReport report = importService.importIcd10(csv(
                    "code,name",
                    "L20.9,Viem da co dia",
                    ",Khong co ma",
                    "L40,Vay nen"));

            assertThat(report.added()).isEqualTo(2);
            assertThat(report.rejected()).hasSize(1);
            assertThat(icd10Repository.findByCode("L40")).isPresent();
        }

        /** With the line number, so the operator can find it without counting. */
        @Test
        void areReportedWithTheirLineNumber() {
            CatalogueImportReport report = importService.importIcd10(csv(
                    "code,name",
                    "L20.9,Viem da co dia",
                    ",Khong co ma"));

            assertThat(report.rejected().get(0).line()).isEqualTo(3);
            assertThat(report.rejected().get(0).reason()).isNotBlank();
        }

        @Test
        void areRejectedForAnInvalidCategory() {
            CatalogueImportReport report = importService.importAllergens(csv(
                    "code,name,category",
                    "PNC,Penicillin,KHONG_HOP_LE"));

            assertThat(report.rejected()).hasSize(1);
            assertThat(report.rejected().get(0).reason()).contains("DRUG");
            assertThat(report.added()).isZero();
        }

        @Test
        void areRejectedForAnUnknownAllergenReference() {
            CatalogueImportReport report = importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength,dosage_form,route,unit,"
                            + "control_class,allergen_codes",
                    "T001,,Amoxicillin,Augmentin,500mg,vien nen,uong,vien,,KHONG-CO"));

            // Importing the drug without its allergen links would leave the
            // allergy check silently incomplete, which is worse than refusing.
            assertThat(report.rejected()).hasSize(1);
            assertThat(report.rejected().get(0).reason())
                    .contains("allergen code not found");
            assertThat(drugRepository.findByCode("T001")).isEmpty();
        }

        @Test
        void areRejectedForAnInvalidControlClass() {
            CatalogueImportReport report = importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength,dosage_form,route,unit,"
                            + "control_class",
                    "T002,,Morphin,,10mg,ong,tiem,ong,KHONG_HOP_LE"));

            assertThat(report.rejected()).hasSize(1);
            assertThat(report.rejected().get(0).reason()).contains("NARCOTIC");
        }

        @Test
        void skipsBlankLinesWithoutCountingThem() {
            CatalogueImportReport report = importService.importIcd10(csv(
                    "code,name",
                    "L20.9,Viem da co dia",
                    "",
                    "L40,Vay nen",
                    ""));

            assertThat(report.added()).isEqualTo(2);
            assertThat(report.rejected()).isEmpty();
        }
    }

    // ---------- the file format ----------

    @Nested
    class Parsing {

        /** Drug strengths contain commas: "500mg, 125mg". */
        @Test
        void handlesAQuotedFieldContainingTheDelimiter() {
            importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength",
                    "T003,,Amoxicillin + Acid clavulanic,Augmentin,\"500mg, 125mg\""));

            assertThat(drugRepository.findByCode("T003").orElseThrow().getStrength())
                    .isEqualTo("500mg, 125mg");
        }

        @Test
        void keepsVietnameseDiacritics() {
            importService.importIcd10(csv(
                    "code,name",
                    "L20.9,Viêm da cơ địa"));

            assertThat(icd10Repository.findByCode("L20.9").orElseThrow().getName())
                    .isEqualTo("Viêm da cơ địa");
        }

        @Test
        void treatsOptionalColumnsAsAbsentWhenBlank() {
            importService.importIcd10(csv(
                    "code,name,name_en,chapter",
                    "L40,Vay nen,,"));

            CatalogIcd10 entry = icd10Repository.findByCode("L40").orElseThrow();
            assertThat(entry.getNameEn()).isNull();
            assertThat(entry.getChapter()).isNull();
        }

        /**
         * The first non-blank line is the header by position, not by content, so
         * a catalogue whose first entry is literally called "code" is not
         * silently discarded.
         */
        @Test
        void treatsTheFirstLineAsAHeaderRegardlessOfItsContents() {
            CatalogueImportReport report = importService.importIcd10(csv(
                    "L20.9,Viem da co dia",
                    "L40,Vay nen"));

            assertThat(report.added()).isEqualTo(1);
            assertThat(icd10Repository.findByCode("L20.9")).isEmpty();
            assertThat(icd10Repository.findByCode("L40")).isPresent();
        }
    }

    // ---------- drug and allergen links ----------

    @Nested
    class DrugsAndWhatTheyContain {

        private void importAllergenCatalogue() {
            importService.importAllergens(csv(
                    "code,name,category,parent_code",
                    "BETA-LACTAM,Nhom beta-lactam,DRUG,",
                    "PNC,Penicillin,DRUG,BETA-LACTAM",
                    "AMOX,Amoxicillin,DRUG,BETA-LACTAM"));
        }

        /**
         * Read through {@code findDetailByCode}, which joins the allergens.
         * {@code findByCode} leaves them lazy, and reading a lazy collection once
         * the transaction has ended fails — which is the same reason the
         * endpoints return records rather than entities.
         */
        @Test
        void linksADrugToTheSubstancesItContains() {
            importAllergenCatalogue();

            importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength,dosage_form,route,unit,"
                            + "control_class,allergen_codes",
                    "T010,QG-001,Amoxicillin,Augmentin,500mg,vien nen,uong,vien,,AMOX;PNC"));

            CatalogDrug drug = drugRepository.findDetailByCode("T010").orElseThrow();
            assertThat(drug.getAllergens())
                    .extracting(CatalogAllergen::getCode)
                    .containsExactlyInAnyOrder("AMOX", "PNC");
        }

        /**
         * The query the allergy check at prescribing time runs: given what a
         * patient reacts to, which drugs must not be prescribed.
         */
        @Test
        void findsEveryDrugContainingASubstance() {
            importAllergenCatalogue();
            importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength,dosage_form,route,unit,"
                            + "control_class,allergen_codes",
                    "T011,,Amoxicillin,Augmentin,500mg,vien,uong,vien,,AMOX",
                    "T012,,Amoxicillin,Ospamox,250mg,vien,uong,vien,,AMOX",
                    "T013,,Paracetamol,Panadol,500mg,vien,uong,vien,,"));

            var amoxicillin = allergenRepository.findByCode("AMOX").orElseThrow();
            List<CatalogDrug> risky =
                    drugRepository.findContainingAnyAllergen(List.of(amoxicillin.getId()));

            assertThat(risky).extracting(CatalogDrug::getCode)
                    .containsExactlyInAnyOrder("T011", "T012");
        }

        @Test
        void recordsTheAllergenClassHierarchy() {
            importAllergenCatalogue();

            var betaLactam = allergenRepository.findByCode("BETA-LACTAM").orElseThrow();
            assertThat(allergenRepository.findByParentIdAndActiveTrue(betaLactam.getId()))
                    .extracting(CatalogAllergen::getCode)
                    .containsExactlyInAnyOrder("PNC", "AMOX");
        }

        @Test
        void marksSpecialControlDrugsAsSuch() {
            importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength,dosage_form,route,unit,"
                            + "control_class",
                    "T020,,Morphin sulfat,,10mg,ong,tiem,ong,NARCOTIC",
                    "T021,,Paracetamol,,500mg,vien,uong,vien,"));

            assertThat(drugRepository.findByCode("T020").orElseThrow().isSpecialControl())
                    .isTrue();
            assertThat(drugRepository.findByCode("T021").orElseThrow().isSpecialControl())
                    .isFalse();
        }

        @Test
        void replacesTheAllergenLinksOnReimportRatherThanAccumulating() {
            importAllergenCatalogue();
            String header = "code,national_code,inn_name,brand_name,strength,dosage_form,"
                    + "route,unit,control_class,allergen_codes";

            importService.importDrugs(csv(header,
                    "T030,,Amoxicillin,Augmentin,500mg,vien,uong,vien,,AMOX;PNC"));
            importService.importDrugs(csv(header,
                    "T030,,Amoxicillin,Augmentin,500mg,vien,uong,vien,,AMOX"));

            // A correction that removes a substance has to remove it, not add to
            // the old set.
            assertThat(drugRepository.findDetailByCode("T030").orElseThrow().getAllergens())
                    .extracting(CatalogAllergen::getCode)
                    .containsExactly("AMOX");
        }
    }

    // ---------- suggestions ----------

    @Nested
    class Suggestions {

        @BeforeEach
        void loadSomeEntries() {
            importService.importIcd10(csv(
                    "code,name,name_en,chapter",
                    "L20.9,Viem da co dia,Atopic dermatitis,XII",
                    "L40,Vay nen,Psoriasis,XII",
                    "J06,Nhiem khuan duong ho hap tren,,X"));

            importService.importDrugs(csv(
                    "code,national_code,inn_name,brand_name,strength,dosage_form,route,unit",
                    "T100,QG-500,Amoxicillin,Augmentin,500mg,vien,uong,vien",
                    "T101,,Paracetamol,Panadol,500mg,vien,uong,vien"));
        }

        /** Clinicians who know a code type the code. */
        @Test
        void matchACodeFromTheStart() {
            assertThat(icd10Repository.suggest("L20", Limit.of(20)))
                    .extracting(CatalogIcd10::getCode)
                    .containsExactly("L20.9");
        }

        /** Everyone else types what the thing is called. */
        @Test
        void matchANameAnywhereInIt() {
            assertThat(icd10Repository.suggest("co dia", Limit.of(20)))
                    .extracting(CatalogIcd10::getCode)
                    .containsExactly("L20.9");
        }

        @Test
        void areCaseInsensitive() {
            assertThat(icd10Repository.suggest("l40", Limit.of(20)))
                    .extracting(CatalogIcd10::getCode)
                    .containsExactly("L40");
        }

        @Test
        void matchTheEnglishNameToo() {
            assertThat(icd10Repository.suggest("Psoriasis", Limit.of(20)))
                    .extracting(CatalogIcd10::getCode)
                    .containsExactly("L40");
        }

        /** A clinician looking for Augmentin types Augmentin, not amoxicillin. */
        @Test
        void matchADrugByItsBrandName() {
            assertThat(drugRepository.suggest("Augmentin", Limit.of(20)))
                    .extracting(CatalogDrug::getCode)
                    .containsExactly("T100");
        }

        @Test
        void matchADrugByItsSubstanceName() {
            assertThat(drugRepository.suggest("amoxi", Limit.of(20)))
                    .extracting(CatalogDrug::getCode)
                    .containsExactly("T100");
        }

        @Test
        void matchADrugByTheNationalCode() {
            assertThat(drugRepository.suggest("QG-500", Limit.of(20)))
                    .extracting(CatalogDrug::getCode)
                    .containsExactly("T100");
        }

        @Test
        void returnNothingForATermThatMatchesNothing() {
            assertThat(drugRepository.suggest("khongcogi", Limit.of(20))).isEmpty();
        }

        @Test
        void respectTheLimit() {
            assertThat(icd10Repository.suggest("", Limit.of(2))).hasSize(2);
        }

        /**
         * Inactive entries are out of suggestions but still resolvable by code,
         * because an old prescription references one and still has to render.
         */
        @Test
        void excludeInactiveEntriesButLeaveThemResolvable() {
            CatalogIcd10 entry = icd10Repository.findByCode("L40").orElseThrow();
            entry.setActive(false);
            icd10Repository.save(entry);

            assertThat(icd10Repository.suggest("L40", Limit.of(20))).isEmpty();
            assertThat(icd10Repository.findByCode("L40")).isPresent();
        }
    }

    // ---------- the CSV splitter on its own ----------

    @Nested
    class CsvSplitting {

        @Test
        void splitsOnCommas() {
            assertThat(CatalogueImportService.splitCsvLine("a,b,c"))
                    .containsExactly("a", "b", "c");
        }

        @Test
        void keepsCommasInsideQuotes() {
            assertThat(CatalogueImportService.splitCsvLine("a,\"b,c\",d"))
                    .containsExactly("a", "b,c", "d");
        }

        @Test
        void unescapesADoubledQuote() {
            assertThat(CatalogueImportService.splitCsvLine("a,\"say \"\"hi\"\"\",c"))
                    .containsExactly("a", "say \"hi\"", "c");
        }

        @Test
        void keepsEmptyFieldsSoColumnPositionsHold() {
            assertThat(CatalogueImportService.splitCsvLine("a,,c"))
                    .containsExactly("a", "", "c");
        }

        @Test
        void trimsSurroundingWhitespace() {
            assertThat(CatalogueImportService.splitCsvLine(" a , b "))
                    .containsExactly("a", "b");
        }
    }
}
