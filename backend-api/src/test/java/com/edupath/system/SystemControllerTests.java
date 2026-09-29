package com.edupath.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupath.common.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemControllerTests {

    @Test
    void healthEndpointReturnsApiResponse() {
        ApiResponse<Map<String, Object>> response = new SystemController().health();

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).containsEntry("service", "backend-api");
        assertThat(response.data()).containsEntry("status", "ok");
    }
}
