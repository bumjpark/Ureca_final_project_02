package com.ureca.myureca.dto.response;

/**
 * 발급 접수(receiptId) 상태 조회 응답.
 *
 * <p>발급 API는 202 ACCEPTED로 접수증({@code receiptId})만 돌려주고 실제 DB 반영은 Kafka
 * Consumer가 비동기로 한다({@code MyCouponQueryService} 클래스 주석 참고). 그동안 클라이언트가
 * 이 receiptId로 곧바로 재조회하면, 지금까지는 "DB에 없음"만 알 수 있었지 그게 "아직 처리
 * 중이라 곧 생길 것"인지 "뭔가 잘못돼서 안 생길 수도 있는 것"인지 구분할 방법이 없었다.
 * 이 응답은 그 둘을 구분한다(다른 팀 프로젝트의 {@code COUPON_NOT_READY} 패턴 비교 조사에서
 * 확인한 갭 — {@code Docs/Airline-Coupon-Architecture-Comparison-2026-08-29.md} §9 참고).
 */
public record CouponIssueStatusResponse(
        String receiptId,
        Status status,
        CouponDetailResponse coupon,
        String note
) {
    public enum Status {
        /** DB에 확정 반영 완료. {@code coupon}에 상세가 채워진다. */
        ISSUED,
        /** 아직 DB에 반영되지 않았고 실패로 확인되지도 않았다 — 정상적인 비동기 처리 중일 수 있다. */
        PENDING,
        /** Kafka 발행/소비가 실패해 reconciliation_log에서 재처리 중이거나 재처리를 소진했다. */
        FAILED
    }

    public static CouponIssueStatusResponse issued(String receiptId, CouponDetailResponse coupon) {
        return new CouponIssueStatusResponse(receiptId, Status.ISSUED, coupon, null);
    }

    public static CouponIssueStatusResponse pending(String receiptId) {
        return new CouponIssueStatusResponse(receiptId, Status.PENDING, null,
                "아직 DB에 반영되지 않았습니다. 정상적인 비동기 처리 중일 수 있으니 잠시 후 다시 조회해주세요.");
    }

    public static CouponIssueStatusResponse failed(String receiptId, String failReason) {
        return new CouponIssueStatusResponse(receiptId, Status.FAILED, null,
                "발급 처리 중 문제가 발생해 재처리 중입니다" + (failReason != null ? ": " + failReason : "."));
    }
}
