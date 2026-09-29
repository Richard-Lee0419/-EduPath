package com.edupath.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceQualityMetricsServiceTests {

    private final ResourceQualityMetricsService service = new ResourceQualityMetricsService(null);

    @Test
    void detectsWindowRegressionAndLowGatePassRateFromPersistedSnapshots() {
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 16, 12, 0, 0, 0, ZoneOffset.ofHours(8));
        List<ResourceRepository.QualityMetricRow> rows = List.of(
                row("old-1", 92, true, 88, now.minusDays(24)),
                row("old-2", 88, true, 84, now.minusDays(20)),
                row("recent-1", 70, false, 42, now.minusDays(4)),
                row("recent-2", 66, false, 38, now.minusDays(2)));

        var response = service.summarize(rows, 1, null, null, 30, now);

        assertThat(response.summary().averageScore()).isEqualTo(79.0);
        assertThat(response.summary().gatePassRate()).isEqualTo(0.5);
        assertThat(response.alerts()).extracting(ResourceQualityMetricsService.QualityAlert::code)
                .contains("GATE_PASS_RATE_LOW", "DIMENSION_SCORE_LOW", "QUALITY_SCORE_REGRESSION");
        assertThat(response.byGenerationMode()).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo("real_model");
            assertThat(item.resourceCount()).isEqualTo(4);
        });
    }

    @Test
    void avoidsRegressionAlertWhenComparisonWindowsHaveTooFewSamples() {
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 16, 12, 0, 0, 0, ZoneOffset.ofHours(8));
        List<ResourceRepository.QualityMetricRow> rows = List.of(
                row("old-1", 95, true, 90, now.minusDays(20)),
                row("recent-1", 70, true, 80, now.minusDays(2)));

        var response = service.summarize(rows, 1, null, null, 30, now);

        assertThat(response.alerts()).extracting(ResourceQualityMetricsService.QualityAlert::code)
                .doesNotContain("QUALITY_SCORE_REGRESSION");
        assertThat(response.summary().evaluationCoverage()).isEqualTo(1.0);
    }

    private ResourceRepository.QualityMetricRow row(
            String id, double totalScore, boolean gatePassed, double evidenceScore, OffsetDateTime createdAt) {
        Map<String, Object> evaluation = Map.of(
                "total_score", totalScore,
                "gate_passed", gatePassed,
                "dimensions", Map.of(
                        "evidence_coverage", Map.of(
                                "score", evidenceScore,
                                "passed", evidenceScore >= 60)));
        return new ResourceRepository.QualityMetricRow(
                id, "lecture", "real_model", evaluation, createdAt);
    }
}
