package com.foody.delivery.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(RegisterRequest request) {
        var passwordHash = passwordEncoder.encode(request.password());
        var user = repository.insert(request.name(), request.email(), passwordHash);
        return UserResponse.from(user);
    }
}
