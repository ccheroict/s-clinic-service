package com.sclinic.appointment;

import com.sclinic.appointment.dto.AppointmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AppointmentMapper {

    @Mapping(target = "patientName", source = "patientName")
    @Mapping(target = "patientPhone", source = "patientPhone")
    @Mapping(target = "doctorName", source = "doctorName")
    AppointmentResponse toResponse(Appointment appointment, String patientName, String patientPhone, String doctorName);
}
