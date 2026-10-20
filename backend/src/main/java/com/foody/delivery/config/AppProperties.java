package com.foody.delivery.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(@DefaultValue Db db, @DefaultValue Jwt jwt, @DefaultValue Cors cors) {

    public record Db(String path, @DefaultValue("5000") int busyTimeoutMs) { }

    public record Jwt(String secretBase64, String issuer, @DefaultValue("3600") long ttlSeconds) {
        @Override
        public String toString() {
            return "Jwt[configuração protegida]";
        }
    }

    public record Cors(List<String> allowedOrigins) { }
}
