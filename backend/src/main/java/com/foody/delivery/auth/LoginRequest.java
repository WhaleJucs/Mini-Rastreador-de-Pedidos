package com.foody.delivery.auth;

import java.util.Locale;

import com.foody.delivery.shared.validation.Utf8ByteLength;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "não deve estar em branco")
        @Email(message = "deve ser um e-mail válido")
        @Size(max = 150, message = "deve ter no máximo 150 caracteres")
        String email,
        @NotBlank(message = "não deve estar em branco")
        @Utf8ByteLength(max = 72)
        String password) {

    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
