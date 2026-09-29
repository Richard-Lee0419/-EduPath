package com.edupath.resource;

import com.edupath.auth.AuthContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class ResourceQualityMetricsService {

    private static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);
    private static final Map<String, String> DIMENSION_LABELS = Map.of(
            "evidence_coverage", "证据覆盖",
            "structural_completeness", "结构完整",
            "knowledge_consistency", "知识一致",
            "difficulty_alignment", "难度匹配",
            "safety_review", "安全审查");

    private final ResourceRepository resourceRepository;

    public ResourceQualityMetricsService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    public QualityMetricsResponse metrics(
            Integer courseId, String resourceType, String generationMode, int windowDays) {
        int normalizedWindow = Math.max(1, Math.min(windowDays, 365));
        OffsetDateTime now = OffsetDateTime.now(CHINA_OFFSET);
        OffsetDateTime since = now.minusDays(normalizedWindow);
        List<ResourceRepository.QualityMetricRow> rows = resourceRepository.listQualityMetrics(
                courseId,
                normalizedFilter(resourceType),
                normalizedFilter(generationMode),
                currentStudentOwnerId(),
                since);
        return summarize(rows, courseId, normalizedFilter(resourceType), normalizedFilter(generationMode), normalizedWindow, now);
    }

    QualityMetricsResponse summarize(
            List<ResourceRepository.QualityMetricRow> rows,
            Integer courseId,
            String resourceType,
            String generationMode,
            int windowDays,
            OffsetDateTime now) {
        List<QualitySample> samples = rows.stream()
                .map(this::toSample)
                .filter(sample -> sample != null)
                .toList();
        List<QualityAlert> alerts = buildAlerts(rows.size(), samples, windowDays, now);
        QualitySummary summary = new QualitySummary(
                rows.size(),
                samples.size(),
                ratio(samples.size(), rows.size()),
                averageScore(samples),
                passRate(samples),
                alerts.size());
        OffsetDateTime freshnessAt = samples.stream()
                .map(QualitySample::createdAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        QualitySource source = new QualitySource(
                "resources.quality_evaluation",
                "一条已生成资源的一次确定性质量评分快照",
                freshnessAt,
                windowDays,
                rows.size(),
                samples.size());
        return new QualityMetricsResponse(
                source,
                summary,
                trend(samples),
                resourceTypeBreakdown(samples),
                generationModeBreakdown(samples),
                dimensions(samples),
                alerts,
                new QualityFilters(courseId, resourceType, generationMode, windowDays),
                List.of(
                        new MetricDefinition("average_score", "平均质量分", "评测样本 total_score 的算术平均值"),
                        new MetricDefinition("gate_pass_rate", "门禁通过率", "gate_passed=true 的评测样本占比"),
                        new MetricDefinition("evaluation_coverage", "评测覆盖率", "含有效质量快照的资源数 / 窗口内资源总数")));
    }

    private List<QualityTrendPoint> trend(List<QualitySample> samples) {
        Map<LocalDate, List<QualitySample>> grouped = new TreeMap<>();
        for (QualitySample sample : samples) {
            grouped.computeIfAbsent(sample.createdAt().toLocalDate(), ignored -> new ArrayList<>()).add(sample);
        }
        return grouped.entrySet().stream()
                .map(entry -> new QualityTrendPoint(
                        entry.getKey(), entry.getValue().size(), averageScore(entry.getValue()), passRate(entry.getValue())))
                .toList();
    }

    private List<QualityBreakdown> resourceTypeBreakdown(List<QualitySample> samples) {
        return breakdown(samples, QualitySample::resourceType);
    }

    private List<QualityBreakdown> generationModeBreakdown(List<QualitySample> samples) {
        return breakdown(samples, QualitySample::generationMode);
    }

    private List<QualityBreakdown> breakdown(
            List<QualitySample> samples, java.util.function.Function<QualitySample, String> classifier) {
        Map<String, List<QualitySample>> grouped = new TreeMap<>();
        for (QualitySample sample : samples) {
            grouped.computeIfAbsent(classifier.apply(sample), ignored -> new ArrayList<>()).add(sample);
        }
        return grouped.entrySet().stream()
                .map(entry -> new QualityBreakdown(
                        entry.getKey(), entry.getValue().size(), averageScore(entry.getValue()), passRate(entry.getValue())))
                .toList();
    }

    private List<QualityDimensionMetric> dimensions(List<QualitySample> samples) {
        Map<String, DimensionAccumulator> grouped = new LinkedHashMap<>();
        DIMENSION_LABELS.keySet().stream().sorted().forEach(key -> grouped.put(key, new DimensionAccumulator()));
        for (QualitySample sample : samples) {
            for (Map.Entry<String, Map<String, Object>> entry : sample.dimensions().entrySet()) {
                Double score = number(entry.getValue().get("score"));
                if (score == null) {
                    continue;
                }
                DimensionAccumulator accumulator = grouped.computeIfAbsent(entry.getKey(), ignored -> new DimensionAccumulator());
                accumulator.count++;
                accumulator.total += score;
                if (booleanValue(entry.getValue().get("passed"))) {
                    accumulator.passed++;
                }
            }
        }
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue().count > 0)
                .map(entry -> new QualityDimensionMetric(
                        entry.getKey(),
                        DIMENSION_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                        entry.getValue().count,
                        round(entry.getValue().total / entry.getValue().count),
                        ratio(entry.getValue().passed, entry.getValue().count)))
                .toList();
    }

    private List<QualityAlert> buildAlerts(
            int eligibleResources, List<QualitySample> samples, int windowDays, OffsetDateTime now) {
        List<QualityAlert> alerts = new ArrayList<>();
        if (eligibleResources >= 3 && ratio(samples.size(), eligibleResources) < 0.8) {
            alerts.add(new QualityAlert(
                    "EVALUATION_COVERAGE_LOW",
                    "warning",
                    "质量评测覆盖率偏低",
                    "部分资源缺少有效质量快照，趋势结论需谨慎使用。",
                    ratio(samples.size(), eligibleResources),
                    0.8,
                    null));
        }
        if (samples.size() >= 3 && passRate(samples) < 0.7) {
            alerts.add(new QualityAlert(
                    "GATE_PASS_RATE_LOW",
                    passRate(samples) < 0.5 ? "critical" : "warning",
                    "质量门禁通过率偏低",
                    "建议优先检查未通过资源的失败维度并触发定向修订。",
                    passRate(samples),
                    0.7,
                    null));
        }
        for (QualityDimensionMetric dimension : dimensions(samples)) {
            if (dimension.resourceCount() >= 2 && dimension.averageScore() < 70.0) {
                alerts.add(new QualityAlert(
                        "DIMENSION_SCORE_LOW",
                        dimension.averageScore() < 60.0 ? "critical" : "warning",
                        dimension.label() + "维度低于基线",
                        "该维度连续样本均值偏低，建议检查生成提示词、RAG 证据和结构校验。",
                        dimension.averageScore(),
                        70.0,
                        dimension.dimension()));
            }
        }
        OffsetDateTime midpoint = now.minusDays(Math.max(1, windowDays / 2));
        List<QualitySample> previous = samples.stream().filter(sample -> sample.createdAt().isBefore(midpoint)).toList();
        List<QualitySample> recent = samples.stream().filter(sample -> !sample.createdAt().isBefore(midpoint)).toList();
        if (previous.size() >= 2 && recent.size() >= 2) {
            double drop = round(averageScore(previous) - averageScore(recent));
            if (drop >= 5.0) {
                alerts.add(new QualityAlert(
                        "QUALITY_SCORE_REGRESSION",
                        drop >= 10.0 ? "critical" : "warning",
                        "近期资源质量发生回归",
                        "后半窗口平均分较前半窗口下降，建议核对模型、提示词或知识库变更。",
                        drop,
                        5.0,
                        null));
            }
        }
        return alerts;
    }

    private QualitySample toSample(ResourceRepository.QualityMetricRow row) {
        Map<String, Object> evaluation = row.qualityEvaluation();
        Double totalScore = evaluation == null ? null : number(evaluation.get("total_score"));
        if (totalScore == null || row.createdAt() == null) {
            return null;
        }
        Map<String, Map<String, Object>> dimensions = new LinkedHashMap<>();
        Object rawDimensions = evaluation.get("dimensions");
        if (rawDimensions instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    value.forEach((key, item) -> normalized.put(String.valueOf(key), item));
                    dimensions.put(String.valueOf(entry.getKey()), normalized);
                }
            }
        }
        return new QualitySample(
                row.resourceType() == null ? "unknown" : row.resourceType(),
                row.generationMode() == null ? "unknown" : row.generationMode(),
                totalScore,
                booleanValue(evaluation.get("gate_passed")),
                dimensions,
                row.createdAt());
    }

    private Long currentStudentOwnerId() {
        var principal = AuthContext.current();
        return principal != null && "student".equals(principal.role()) ? principal.userId() : null;
    }

    private String normalizedFilter(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? null : value.trim();
    }

    private double averageScore(List<QualitySample> samples) {
        return samples.isEmpty()
                ? 0.0
                : round(samples.stream().mapToDouble(QualitySample::totalScore).average().orElse(0.0));
    }

    private double passRate(List<QualitySample> samples) {
        return samples.isEmpty()
                ? 0.0
                : ratio(samples.stream().filter(QualitySample::gatePassed).count(), samples.size());
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round((double) numerator / denominator);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static final class DimensionAccumulator {
        private int count;
        private int passed;
        private double total;
    }

    private record QualitySample(
            String resourceType,
            String generationMode,
            double totalScore,
            boolean gatePassed,
            Map<String, Map<String, Object>> dimensions,
            OffsetDateTime createdAt) {}

    public record QualityMetricsResponse(
            QualitySource source,
            QualitySummary summary,
            List<QualityTrendPoint> trend,
            List<QualityBreakdown> byResourceType,
            List<QualityBreakdown> byGenerationMode,
            List<QualityDimensionMetric> dimensions,
            List<QualityAlert> alerts,
            QualityFilters filters,
            List<MetricDefinition> metricDefinitions) {}

    public record QualitySource(
            String sourceTable,
            String grain,
            OffsetDateTime freshnessAt,
            int windowDays,
            int eligibleResources,
            int evaluatedResources) {}

    public record QualitySummary(
            int totalResources,
            int evaluatedResources,
            double evaluationCoverage,
            double averageScore,
            double gatePassRate,
            int alertCount) {}

    public record QualityTrendPoint(LocalDate date, int resourceCount, double averageScore, double gatePassRate) {}

    public record QualityBreakdown(String key, int resourceCount, double averageScore, double gatePassRate) {}

    public record QualityDimensionMetric(
            String dimension, String label, int resourceCount, double averageScore, double passRate) {}

    public record QualityAlert(
            String code,
            String severity,
            String title,
            String message,
            double observedValue,
            double threshold,
            String dimension) {}

    public record QualityFilters(Integer courseId, String resourceType, String generationMode, int windowDays) {}

    public record MetricDefinition(String key, String label, String definition) {}
}
