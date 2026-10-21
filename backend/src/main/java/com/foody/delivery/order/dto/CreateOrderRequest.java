package com.foody.delivery.order.dto;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank(message = "Informe o nome do cliente.")
        @Size(max = 100, message = "Use no máximo 100 caracteres.")
        String customerName,
        @NotNull(message = "Informe o endereço de entrega.") @Valid
        AddressRequest deliveryAddress,
        @NotEmpty(message = "Informe pelo menos um item.")
        List<@NotNull(message = "Informe o item.") @Valid ItemRequest> items) {

    public CreateOrderRequest {
        customerName = trim(customerName);
    }

    public record AddressRequest(
            @NotBlank(message = "Informe a rua.")
            @Size(max = 150, message = "Use no máximo 150 caracteres.")
            String street,
            @NotBlank(message = "Informe o número ou S/N.")
            @Size(max = 30, message = "Use no máximo 30 caracteres.")
            String number,
            @NotBlank(message = "Informe a cidade.")
            @Size(max = 150, message = "Use no máximo 150 caracteres.")
            String city,
            @NotBlank(message = "Informe a UF.")
            @Pattern(regexp = "[A-Z]{2}", message = "Informe uma UF com duas letras.")
            String state,
            @Pattern(regexp = "[0-9]{8}", message = "Informe um CEP com oito dígitos.")
            String zipCode) {

        public AddressRequest {
            street = trim(street);
            number = trim(number);
            city = trim(city);
            state = state == null ? null : state.trim().toUpperCase(Locale.ROOT);
            zipCode = trim(zipCode);
            if (zipCode != null && zipCode.matches("[0-9]{5}-[0-9]{3}")) {
                zipCode = zipCode.replace("-", "");
            }
        }
    }

    public record ItemRequest(
            @NotBlank(message = "Informe o nome do produto.")
            @Size(max = 100, message = "Use no máximo 100 caracteres.")
            String productName,
            @NotNull(message = "Informe uma quantidade inteira positiva.")
            @Positive(message = "Informe uma quantidade inteira positiva.")
            @JsonDeserialize(using = QuantityDeserializer.class)
            Integer quantity) {

        public ItemRequest {
            productName = trim(productName);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
