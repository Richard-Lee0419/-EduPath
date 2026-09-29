package com.edupath.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.RoleGuard;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoPreflightServiceTests {

    @Test
    void allReadyDependenciesSelectLiveAiMode() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiAgentClient aiAgentClient = mock(AiAgentClient.class);
        DemoScenarioRepository repository = mock(DemoScenarioRepository.class);
        RoleGuard roleGuard = mock(RoleGuard.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(repository.currentSummary())
                .thenReturn(new DemoScenarioRepository.DemoDataSummary(
                        true, 1, 1, 6, 1, 1, 1, 8, 3, 2, OffsetDateTime.now()));
        when(aiAgentClient.readiness())
                .thenReturn(Map.of(
                        "status", "ready",
                        "live_generation_ready", true,
                        "prepared_demo_supported", true,
                        "checks", Map.of(
                                "llm_configured", true,
                                "embedding_configured", true,
                                "course_corpus_ready", true),
                        "corpus", Map.of(
                                "chunks", 200,
                                "external_documents", 20,
                                "mode", "course_corpus")));

        DemoPreflightService.DemoPreflightResult result =
                new DemoPreflightService(jdbcTemplate, aiAgentClient, repository, roleGuard).inspect();

        assertThat(result.overallStatus()).isEqualTo("ready");
        assertThat(result.recommendedMode()).isEqualTo("live_ai");
        assertThat(result.preparedDemoAvailable()).isTrue();
        assertThat(result.liveAiAvailable()).isTrue();
        assertThat(result.unavailableCapabilities()).isEmpty();
    }

    @Test
    void unreachableAiKeepsPreparedDemoAvailableInDegradedMode() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiAgentClient aiAgentClient = mock(AiAgentClient.class);
        DemoScenarioRepository repository = mock(DemoScenarioRepository.class);
        RoleGuard roleGuard = mock(RoleGuard.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(repository.currentSummary())
                .thenReturn(new DemoScenarioRepository.DemoDataSummary(
                        true, 1, 1, 6, 1, 1, 1, 8, 3, 2, OffsetDateTime.now()));
        when(aiAgentClient.readiness()).thenThrow(new RuntimeException("AI service offline"));

        DemoPreflightService.DemoPreflightResult result =
                new DemoPreflightService(jdbcTemplate, aiAgentClient, repository, roleGuard).inspect();

        assertThat(result.overallStatus()).isEqualTo("degraded");
        assertThat(result.recommendedMode()).isEqualTo("prepared_data_only");
        assertThat(result.preparedDemoAvailable()).isTrue();
        assertThat(result.liveAiAvailable()).isFalse();
        assertThat(result.unavailableCapabilities()).hasSize(3);
        assertThat(result.checks())
                .anySatisfy(check -> {
                    assertThat(check.key()).isEqualTo("ai_service");
                    assertThat(check.status()).isEqualTo("degraded");
                    assertThat(check.required()).isFalse();
                });
    }
}
