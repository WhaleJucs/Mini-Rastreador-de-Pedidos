package com.foody.delivery.order.dto;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.foody.delivery.order.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record StatusUpdateRequest(
        @NotNull(message = "Informe o status do pedido.")
        @Schema(description = "Novo status permitido pela sequência do pedido", example = "EM_PREPARO")
        @JsonDeserialize(using = StatusDeserializer.class)
        OrderStatus status,
        @NotNull(message = "Informe a revisão observada do pedido.")
        @PositiveOrZero(message = "Informe uma revisão inteira não negativa.")
        @Schema(description = "Versão recebida na última leitura do pedido; evita sobrescrever alterações concorrentes", example = "0")
        @JsonDeserialize(using = VersionDeserializer.class)
        Long expectedVersion) {

    public static class StatusDeserializer extends JsonDeserializer<OrderStatus> {
        @Override
        public OrderStatus deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (OrderStatus) context.handleUnexpectedToken(OrderStatus.class, parser);
            }
            try {
                return OrderStatus.valueOf(parser.getText());
            } catch (IllegalArgumentException failure) {
                return (OrderStatus) context.handleWeirdStringValue(OrderStatus.class, parser.getText(),
                        "Informe um status válido.");
            }
        }
    }

    public static class VersionDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}
