package com.ureca.myureca.dto.response;

/** 발급 추이 그래프의 한 점(1분 단위 버킷). bucket은 "yyyy-MM-dd HH:mm:00" 형식. */
public record IssuanceTimelinePointResponse(String bucket, long count) {
}
