package com.sclinic.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Checks an encounter's clinical data against the schema version it names.
 *
 * <p>Requirement 13.4. The point of validating at all is that the clinical part
 * of the record is free-form JSON as far as the database is concerned: without a
 * check, a typo in a field name writes a field nobody will ever read, and a
 * severity of "very bad" is stored as happily as one of "SEVERE". Neither failure
 * announces itself, and both are discovered when somebody tries to use the data.
 */
@Component
@RequiredArgsConstructor
public class ClinicalDataValidator {

    private final ObjectMapper objectMapper;

    /**
     * Compiled schemas, keyed by the row they came from.
     *
     * <p>Compiling a JSON Schema is not cheap and these rows never change once
     * published — a correction is a new version with a new id — so the cache
     * cannot go stale. Keyed by id rather than by specialty so that validating an
     * old record against an old version is a cache hit too.
     */
    private final Map<UUID, JsonSchema> compiled = new ConcurrentHashMap<>();

    /**
     * @throws ClinicalDataInvalidException listing every field that failed, not
     *         just the first
     */
    public void validate(Map<String, Object> clinicalData, ClinicalDataSchema schema) {
        if (clinicalData == null || clinicalData.isEmpty()) {
            // Nothing recorded yet. A draft with no clinical data is a normal
            // state — the doctor has started the record and not reached that part
            // — and requiring it here would mean the record could not be created
            // before it was complete.
            return;
        }

        JsonNode data = objectMapper.valueToTree(clinicalData);
        Set<ValidationMessage> failures = schemaFor(schema).validate(data);

        if (!failures.isEmpty()) {
            throw new ClinicalDataInvalidException(
                    schema.getSpecialtyCode(),
                    schema.getVersion(),
                    failures.stream()
                            .map(ValidationMessage::getMessage)
                            .sorted()
                            .collect(Collectors.toList()));
        }
    }

    private JsonSchema schemaFor(ClinicalDataSchema schema) {
        return compiled.computeIfAbsent(schema.getId(), id -> {
            JsonNode schemaNode = objectMapper.valueToTree(schema.getSchema());

            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    // Report every failure rather than stopping at the first.
                    // A doctor fixing a form wants the list, not one item of it
                    // per attempt.
                    .failFast(false)
                    .build();

            // Draft 2020-12, fixed rather than read from the document's $schema.
            // Letting a stored schema choose its own dialect would let a schema
            // select a weaker one, and "which dialect validated this record" is
            // not a question a clinic should have to answer per row.
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode, config);
        });
    }
}
