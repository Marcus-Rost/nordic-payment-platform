package com.jrpay.billpay.customer.mandates.dto;

import java.util.List;

public record MandateExecutionPage(List<MandateExecutionDto> items, Page page) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
