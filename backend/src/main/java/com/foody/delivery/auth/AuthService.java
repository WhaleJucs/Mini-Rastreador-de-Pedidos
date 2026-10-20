package com.foody.delivery.auth;

import java.time.Clock;
import java.time.Instant;

import com.foody.delivery.config.AppProperties;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties.Jwt jwtProperties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(
            AuthRepository repository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AppProperties properties,
            Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        jwtProperties = properties.jwt();
        this.clock = clock;
        dummyPasswordHash = passwordEncoder.encode("foody-invalid-credentials-dummy");
    }

    public UserResponse register(RegisterRequest request) {
        var passwordHash = passwordEncoder.encode(request.password());
        var user = repository.insert(request.name(), request.email(), passwordHash);
        return UserResponse.from(user);
    }

    public LoginResponse login(LoginRequest request) {
        var candidate = repository.findByEmail(request.email());
        var passwordHash = candidate.map(User::passwordHash).orElse(dummyPasswordHash);
        var passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        if (candidate.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        var user = candidate.orElseThrow();
        var issuedAt = Instant.now(clock);
        var expiresAt = issuedAt.plusSeconds(jwtProperties.ttlSeconds());
        var claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(Long.toString(user.id()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(
                accessToken,
                "Bearer",
                jwtProperties.ttlSeconds(),
                UserResponse.from(user));
    }
}
