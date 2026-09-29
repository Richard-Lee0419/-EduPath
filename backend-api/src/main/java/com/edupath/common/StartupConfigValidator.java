package com.edupath.common;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupConfigValidator {

    private final Environment environment;

    public StartupConfigValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prod) {
            return;
        }
        require("DB_URL");
        require("DB_USERNAME");
        require("DB_PASSWORD");
        String jwtSecret = require("JWT_SECRET");
        if (jwtSecret.length() < 32 || jwtSecret.contains("dev-change-me")) {
            throw new IllegalStateException("prod 环境 JWT_SECRET 必须至少 32 位且不能使用开发默认值");
        }
        require("AI_SERVICE_BASE_URL");
        String signingSecret = require("STORAGE_SIGNING_SECRET");
        if (signingSecret.length() < 32 || signingSecret.contains("dev-edupath") || signingSecret.equals(jwtSecret)) {
            throw new IllegalStateException("prod 环境 STORAGE_SIGNING_SECRET 必须独立配置、至少 32 位且不能使用开发默认值");
        }
        String storageProvider = environment.getProperty("STORAGE_PROVIDER", "oss");
        if (!"oss".equalsIgnoreCase(storageProvider)) {
            throw new IllegalStateException("prod 环境 STORAGE_PROVIDER 必须使用 oss，不能使用本地文件存储");
        }
        require("OSS_BUCKET");
        require("OSS_ENDPOINT");
        require("OSS_ACCESS_KEY_ID");
        require("OSS_ACCESS_KEY_SECRET");
        if (enabled("EMAIL_ENABLED", "edupath.email.enabled")) {
            String mailUsername = require("MAIL_USERNAME");
            String mailPassword = require("MAIL_PASSWORD");
            if (mailUsername.contains("CHANGE_ME") || mailPassword.contains("CHANGE_ME")) {
                throw new IllegalStateException("prod 环境启用邮件时 MAIL_USERNAME/MAIL_PASSWORD 不能使用占位值");
            }
            String host = environment.getProperty("MAIL_HOST", "smtp.qq.com");
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("prod 环境启用邮件时缺少必要配置: MAIL_HOST");
            }
        }
    }

    private String require(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("prod 环境缺少必要配置: " + key);
        }
        return value;
    }

    private boolean enabled(String envKey, String propertyKey) {
        return Boolean.parseBoolean(environment.getProperty(envKey, environment.getProperty(propertyKey, "false")));
    }
}
