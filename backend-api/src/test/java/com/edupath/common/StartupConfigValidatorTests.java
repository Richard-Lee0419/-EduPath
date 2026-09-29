package com.edupath.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupConfigValidatorTests {

    @Test
    void prodValidationRequiresAiServiceBaseUrl() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("STORAGE_PROVIDER", "local")
                .withProperty("STORAGE_SIGNING_SECRET", "storage-signing-secret-with-at-least-32-chars");

        assertThatThrownBy(() -> new StartupConfigValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_SERVICE_BASE_URL");
    }

    @Test
    void prodValidationRejectsDefaultStorageSigningSecret() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("AI_SERVICE_BASE_URL", "http://ai-agent-service:8000")
                .withProperty("STORAGE_PROVIDER", "local")
                .withProperty("STORAGE_SIGNING_SECRET", "dev-edupath-storage-signing-secret");

        assertThatThrownBy(() -> new StartupConfigValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_SIGNING_SECRET");
    }

    @Test
    void prodValidationRejectsLocalObjectStorage() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("AI_SERVICE_BASE_URL", "http://ai-agent-service:8000")
                .withProperty("STORAGE_PROVIDER", "local")
                .withProperty("STORAGE_SIGNING_SECRET", "storage-signing-secret-with-at-least-32-chars");

        assertThatThrownBy(() -> new StartupConfigValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_PROVIDER");
    }

    @Test
    void prodValidationAcceptsCompleteOssStorageConfiguration() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("AI_SERVICE_BASE_URL", "http://ai-agent-service:8000")
                .withProperty("STORAGE_PROVIDER", "oss")
                .withProperty("STORAGE_SIGNING_SECRET", "storage-signing-secret-with-at-least-32-chars")
                .withProperty("OSS_BUCKET", "edupath-prod")
                .withProperty("OSS_ENDPOINT", "https://oss-cn-hangzhou.aliyuncs.com")
                .withProperty("OSS_ACCESS_KEY_ID", "access-key-id")
                .withProperty("OSS_ACCESS_KEY_SECRET", "access-key-secret");

        assertThatCode(() -> new StartupConfigValidator(environment).validate()).doesNotThrowAnyException();
    }

    @Test
    void prodValidationRequiresMailCredentialsWhenEmailIsEnabled() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("AI_SERVICE_BASE_URL", "http://ai-agent-service:8000")
                .withProperty("STORAGE_PROVIDER", "oss")
                .withProperty("STORAGE_SIGNING_SECRET", "storage-signing-secret-with-at-least-32-chars")
                .withProperty("OSS_BUCKET", "edupath-prod")
                .withProperty("OSS_ENDPOINT", "https://oss-cn-hangzhou.aliyuncs.com")
                .withProperty("OSS_ACCESS_KEY_ID", "access-key-id")
                .withProperty("OSS_ACCESS_KEY_SECRET", "access-key-secret")
                .withProperty("EMAIL_ENABLED", "true")
                .withProperty("MAIL_USERNAME", "sender@qq.com");

        assertThatThrownBy(() -> new StartupConfigValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PASSWORD");
    }

    private MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment
                .withProperty("DB_URL", "jdbc:mysql://db:3306/edupath")
                .withProperty("DB_USERNAME", "edupath")
                .withProperty("DB_PASSWORD", "edupath-password")
                .withProperty("JWT_SECRET", "jwt-secret-with-at-least-32-characters");
    }
}
