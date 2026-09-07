package com.jrpay.fraud.casework.fraud.dto;

import java.util.List;

public record FraudCasePage(List<FraudCaseSummaryDto> items, Page page, Counters counters) {

    public record Page(int number, int size, long totalElements, int totalPages) {}

    public record Counters(long openTotal, long assignedToMe, long slaBreached) {}
}
