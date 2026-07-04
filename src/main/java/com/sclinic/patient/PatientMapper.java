package com.sclinic.patient;

import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface PatientMapper {

    PatientResponse toResponse(Patient patient);

    Patient toEntity(PatientRequest request);

    /** Update in place, ignoring null fields so PUT acts as a partial-safe update. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(PatientRequest request, @MappingTarget Patient patient);
}
