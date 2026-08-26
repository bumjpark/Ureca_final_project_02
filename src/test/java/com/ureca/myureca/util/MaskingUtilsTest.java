package com.ureca.myureca.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaskingUtilsTest {

    @DisplayName("이름은 첫 글자와 마지막 글자만 남긴다")
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "김,        *",
            "김철,      김*",
            "홍길동,    홍*동",
            "남궁민수,  남**수"
    })
    void maskName(String raw, String expected) {
        assertEquals(expected, MaskingUtils.maskName(raw));
    }

    @DisplayName("이메일은 계정 앞 2글자만 남기고 도메인은 유지한다")
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "pcy9849@gmail.com, pc*****@gmail.com",
            "ab@gmail.com,      **@gmail.com",
            "a@gmail.com,       *@gmail.com"
    })
    void maskEmail(String raw, String expected) {
        assertEquals(expected, MaskingUtils.maskEmail(raw));
    }

    @DisplayName("이메일 형식이 아니면 통째로 가린다")
    @Test
    void maskEmail_notAnEmail() {
        assertEquals("*****", MaskingUtils.maskEmail("hello"));
    }

    @DisplayName("null 과 빈 문자열은 그대로 통과시킨다")
    @Test
    void maskNullAndBlank() {
        assertNull(MaskingUtils.maskName(null));
        assertNull(MaskingUtils.maskEmail(null));
        assertEquals("", MaskingUtils.maskName(""));
        assertEquals("", MaskingUtils.maskEmail(""));
    }
}
