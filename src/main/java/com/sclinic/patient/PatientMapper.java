package com.sclinic.patient;

import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface PatientMapper {

    PatientResponse toResponse(Patient patient);

    Patient toEntity(PatientRequest request);

    /**
     * Update in place, ignoring null fields so PUT acts as a partial-safe update.
     *
     * <p>The withdrawal and retention fields are not mappable from a request:
     * withdrawing a record goes through {@code PatientService.delete} so the
     * reason and the actor are recorded, and the retention date is derived from
     * policy rather than supplied by the caller. Listing them as ignored means a
     * future field added to the request cannot quietly become a way to set them.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    @Mapping(target = "deleteReason", ignore = true)
    @Mapping(target = "retentionUntil", ignore = true)
    @Mapping(target = "legalHold", ignore = true)
    void updateEntity(PatientRequest request, @MappingTarget Patient patient);
}
