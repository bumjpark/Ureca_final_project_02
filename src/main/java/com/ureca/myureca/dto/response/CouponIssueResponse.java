package com.ureca.myureca.dto.response;

public record CouponIssueResponse(
        String status,
        String receiptId,
        String message) {
    public static CouponIssueResponse accepted(String receiptId) {
        return new CouponIssueResponse("ACCEPTED", receiptId, "접수 완료");
    }
}
