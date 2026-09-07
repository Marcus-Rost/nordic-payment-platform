package com.jrpay.fraud.casework.sanctions.dto;

import java.util.List;

public record SanctionsHitPage(List<SanctionsHitSummaryDto> items, Page page, Counters counters) {

    public record Page(int number, int size, long totalElements, int totalPages) {}

    public record Counters(long openTotal, long slaBreached) {}
}
