package com.ureca.myureca.exception;

public class UserNotFoundException extends RuntimeException {

    private final Long userId;

    public UserNotFoundException(Long userId) {
        super("존재하지 않는 사용자입니다. userId=" + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
