package com.sclinic.consent.dto;

import jakarta.validation.constraints.Size;

/**
 * Recording a withdrawal.
 *
 * <p>The note is optional, and the whole body is. A patient's right to withdraw
 * consent is unconditional under NĐ 13/2023; a mandatory reason field would be
 * the system asking them to justify exercising it.
 */
public record ConsentWithdrawRequest(

        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
