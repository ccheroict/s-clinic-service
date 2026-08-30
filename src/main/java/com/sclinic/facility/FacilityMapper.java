package com.sclinic.facility;

import com.sclinic.facility.dto.FacilityRequest;
import com.sclinic.facility.dto.FacilityResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface FacilityMapper {

    FacilityResponse toResponse(Facility facility);

    /** Update in place, ignoring null fields so PUT acts as a partial-safe update. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(FacilityRequest request, @MappingTarget Facility facility);
}
