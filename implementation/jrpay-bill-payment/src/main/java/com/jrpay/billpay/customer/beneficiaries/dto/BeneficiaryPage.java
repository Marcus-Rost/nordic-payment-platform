package com.jrpay.billpay.customer.beneficiaries.dto;

import java.util.List;

public record BeneficiaryPage(List<BeneficiaryDto> items, Page page) {

    public record Page(int number, int size, long totalElements, int totalPages) {}
}
