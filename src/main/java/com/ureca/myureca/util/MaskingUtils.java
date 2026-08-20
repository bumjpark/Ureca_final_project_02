package com.ureca.myureca.util;

/**
 * 개인정보 마스킹 (FR-2 / NFR-5).
 *
 * API 응답뿐 아니라 로그에 개인정보를 남길 때도 이 유틸을 거치도록 한다.
 * 마스킹 규칙이 여기 한 곳에만 있어야 "어떤 경로는 마스킹이 빠져 있었다"가 생기지 않는다.
 */
public final class MaskingUtils {

    private static final char MASK = '*';

    private MaskingUtils() {
    }

    /**
     * 이름 마스킹: 첫 글자와 마지막 글자만 남긴다.
     * "홍길동"   → "홍*동"
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        int length = name.length();
        if (length == 1) {
            return String.valueOf(MASK);
        }
        if (length == 2) {
            return name.charAt(0) + String.valueOf(MASK);
        }
        return name.charAt(0) + repeat(length - 2) + name.charAt(length - 1);
    }

    /**
     * 이메일 마스킹: 계정 앞 2글자만 남기고 도메인은 그대로 둔다.
     * "pcy9849@gmail.com" → "pc*****@gmail.com"
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at < 0) {
            // 이메일 형식이 아니면 판단하지 말고 통째로 가린다
            return repeat(email.length());
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return repeat(local.length()) + domain;
        }
        return local.substring(0, 2) + repeat(local.length() - 2) + domain;
    }

    private static String repeat(int count) {
        return String.valueOf(MASK).repeat(count);
    }
}
