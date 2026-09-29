package com.edupath;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class EduPathBackendApplicationTests {

    @Test
    void applicationClassDeclaresSpringBootApplication() {
        assertThat(EduPathBackendApplication.class.getAnnotation(SpringBootApplication.class)).isNotNull();
    }
}
