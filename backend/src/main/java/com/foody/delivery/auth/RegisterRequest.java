package com.foody.delivery.auth;

import java.util.Locale;

import com.foody.delivery.shared.validation.Utf8ByteLength;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "não deve estar em branco")
        @Size(max = 100, message = "deve ter no máximo 100 caracteres")
        String name,
        @NotBlank(message = "não deve estar em branco")
        @Email(message = "deve ser um e-mail válido")
        @Size(max = 150, message = "deve ter no máximo 150 caracteres")
        String email,
        @NotBlank(message = "não deve estar em branco")
        @Utf8ByteLength(max = 72)
        String password) {

    public RegisterRequest {
        name = normalizeName(name);
        email = normalizeEmail(email);
    }

    private static String normalizeName(String value) {
        return value == null ? null : value.trim();
    }

    private static String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
