package com.foody.delivery.config;

import java.time.Clock;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretKey jwtSecretKey(AppProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local"))
                && (properties.jwt().secretBase64() == null || properties.jwt().secretBase64().isBlank())) {
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return new SecretKeySpec(bytes, "HmacSHA256");
        }
        var decoded = Base64.getDecoder().decode(properties.jwt().secretBase64());
        return new SecretKeySpec(decoded, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(AppProperties properties, Clock clock, SecretKey jwtSecretKey) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        var timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                timestampValidator,
                new JwtIssuerValidator(properties.jwt().issuer()),
                new JwtClaimValidator<String>("sub", JwtConfig::isUserId)));
        return decoder;
    }

    private static boolean isUserId(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]*")) return false;
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException failure) {
            return false;
        }
    }
}
