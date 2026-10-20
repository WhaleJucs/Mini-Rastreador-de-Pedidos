package com.foody.delivery.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {
}
