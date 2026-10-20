package com.foody.delivery.auth;

public record UserResponse(long id, String name, String email) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.name(), user.email());
    }
}
