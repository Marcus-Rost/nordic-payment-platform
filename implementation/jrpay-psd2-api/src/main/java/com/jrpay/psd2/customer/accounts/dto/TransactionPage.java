package com.jrpay.psd2.customer.accounts.dto;

import java.util.List;

public record TransactionPage(List<TransactionSummaryDto> items, Page page) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
