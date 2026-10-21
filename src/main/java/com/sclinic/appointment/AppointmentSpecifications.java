package com.sclinic.appointment;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Filters for the appointment list, built from whichever ones were supplied.
 *
 * <p>This replaces a JPQL query written as
 * {@code (:doctorId is null or a.doctorId = :doctorId)} repeated per filter.
 * That shape looks like it handles optional parameters and does not: it compiles
 * to {@code (? is null or ...)}, PostgreSQL cannot infer a type for a bare
 * parameter compared against null, and the whole statement fails with a grammar
 * error. The endpoint only worked when a client supplied every filter — which is
 * not what any client does — and the fault survived because the service test
 * mocks the repository, so no SQL was ever executed against a database.
 *
 * <p>Building the predicate instead means a filter that was not supplied
 * contributes no clause at all. Nothing is compared against null, so there is
 * nothing for the driver to infer, and the statement contains only the
 * conditions that were actually asked for — which also lets the planner use the
 * indexes on {@code scheduled_at} and {@code doctor_id} rather than working
 * through a chain of {@code or} clauses that can never be satisfied.
 */
final class AppointmentSpecifications {

    private AppointmentSpecifications() {
    }

    /**
     * @param from     inclusive lower bound on the appointment time, or null
     * @param to       exclusive upper bound, or null
     * @param doctorId restrict to one doctor, or null for all
     * @param status   restrict to one status, or null for all
     */
    static Specification<Appointment> matching(Instant from, Instant to, UUID doctorId,
                                               AppointmentStatus status) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(4);

            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("scheduledAt"), from));
            }
            if (to != null) {
                // Exclusive, so a day filter cannot pick up the first appointment
                // of the next day.
                predicates.add(builder.lessThan(root.get("scheduledAt"), to));
            }
            if (doctorId != null) {
                predicates.add(builder.equal(root.get("doctorId"), doctorId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }

            // No filters is a legitimate request — it is what the list screen
            // asks for on open — and an empty conjunction is the identity, so it
            // matches everything rather than nothing.
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
