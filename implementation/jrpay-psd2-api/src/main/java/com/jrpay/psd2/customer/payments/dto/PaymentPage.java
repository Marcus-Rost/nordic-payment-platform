package com.jrpay.psd2.customer.payments.dto;

import java.util.List;

public record PaymentPage(List<PaymentRecordDto> items, Page page) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
