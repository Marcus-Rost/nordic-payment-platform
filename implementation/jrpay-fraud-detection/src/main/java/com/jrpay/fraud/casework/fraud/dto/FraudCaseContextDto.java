package com.jrpay.fraud.casework.fraud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FraudCaseContextDto(
        int windowDays,
        Money totalVolume,
        int uniqueCounterparties,
        int newBeneficiariesCount,
        Money avgTransactionSize,
        List<Deviation> deviations
) {

    public record Money(String amount, String currency) {}

    public record Deviation(String indicator, String severity, String note) {}
}
