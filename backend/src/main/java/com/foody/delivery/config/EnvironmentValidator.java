package com.foody.delivery.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

@EnableConfigurationProperties(AppProperties.class)
public class EnvironmentValidator {

    public EnvironmentValidator(AppProperties properties, Environment environment) {
        var profiles = environment.getActiveProfiles();
        require(profiles.length == 1 && Set.of("local", "hml", "prod", "test").contains(profiles[0]),
                "SPRING_PROFILES_ACTIVE deve selecionar somente local, hml ou prod; test é exclusivo do harness.");
        var profile = profiles[0];
        require(!profile.equals("test") || new ClassPathResource("application-test.yml").exists(),
                "O perfil test exige os recursos do harness de testes.");
        boolean remote = profile.equals("hml") || profile.equals("prod");

        var db = properties.db();
        require(db.path() != null && !db.path().isBlank(), "APP_DB_PATH é obrigatório.");
        boolean validPath;
        try {
            var path = db.path();
            validPath = !path.contains(":memory:") && !path.startsWith("jdbc:") && !path.startsWith("file:")
                    && !path.contains("?") && !path.contains("#") && (!remote || Path.of(path).isAbsolute());
            Path.of(path);
        } catch (RuntimeException ignored) {
            validPath = false;
        }
        require(validPath, "APP_DB_PATH deve indicar um arquivo SQLite; hml/prod exigem caminho absoluto.");
        if (profile.equals("prod")) {
            var path = Path.of(db.path()).normalize();
            var parent = path.getParent();
            require(parent != null && Files.isDirectory(parent) && Files.isWritable(parent)
                            && (!Files.exists(path) || (Files.isRegularFile(path) && Files.isWritable(path))),
                    "APP_DB_PATH em prod exige diretório pai existente e gravável, e arquivo SQLite criável ou gravável; prepare o volume externo.");
        }
        require(db.busyTimeoutMs() > 0, "APP_DB_BUSY_TIMEOUT_MS deve ser inteiro positivo e finito.");

        var jwt = properties.jwt();
        boolean validKey = false;
        if (jwt.secretBase64() != null) {
            try {
                validKey = Base64.getDecoder().decode(jwt.secretBase64()).length >= 32;
            } catch (IllegalArgumentException ignored) {
                // Não propagar a causa/entrada: uma chave inválida continua sendo um segredo.
            }
        }
        require(validKey, "APP_JWT_SECRET_BASE64 deve codificar pelo menos 32 bytes em Base64.");
        require(jwt.issuer() != null && !jwt.issuer().isBlank(), "APP_JWT_ISSUER é obrigatório.");
        require(jwt.ttlSeconds() > 0, "APP_JWT_TTL_SECONDS deve ser inteiro positivo.");

        var origins = properties.cors().allowedOrigins();
        require(origins != null && !origins.isEmpty(), "APP_CORS_ALLOWED_ORIGINS exige origens explícitas.");
        require(origins.stream().allMatch(origin -> validOrigin(origin, remote)),
                "APP_CORS_ALLOWED_ORIGINS exige origens HTTP(S) sem caminho/credenciais; hml/prod exigem HTTPS sem loopback.");
    }

    private static boolean validOrigin(String origin, boolean remote) {
        try {
            var uri = URI.create(origin);
            var host = uri.getHost();
            return host != null && !host.contains("*") && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getPort() >= -1 && uri.getPort() <= 65535 && uri.getPort() != 0
                    && Set.of("http", "https").contains(uri.getScheme())
                    && (!remote || (uri.getScheme().equals("https")
                    && !host.equalsIgnoreCase("localhost") && !host.endsWith(".localhost")
                    && !host.startsWith("127.") && !host.equals("[::1]") && !host.equals("0.0.0.0")));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException("Configuração de ambiente inválida: " + message);
        }
    }
}
