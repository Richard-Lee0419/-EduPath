package com.edupath.resource;

import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ResourceTaskRequest(
        @NotNull Integer courseId,
        List<Integer> knowledgePointIds,
        List<String> knowledgePoints,
        String goal,
        List<String> resourceTypes,
        String difficulty) {

    private static final Set<String> ALLOWED_RESOURCE_TYPES = Set.of(
            "lecture", "mindmap", "quiz", "codelab", "animation_script", "flowchart", "reading");
    private static final Set<String> ALLOWED_DIFFICULTIES = Set.of("basic", "medium", "advanced");

    public List<String> normalizedResourceTypes() {
        if (resourceTypes == null || resourceTypes.isEmpty()) {
            return List.of("lecture", "mindmap", "quiz");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String type : resourceTypes) {
            if (type != null && ALLOWED_RESOURCE_TYPES.contains(type.trim())) {
                normalized.add(type.trim());
            }
        }
        return normalized.isEmpty() ? List.of("lecture", "mindmap", "quiz") : List.copyOf(normalized);
    }

    public String normalizedDifficulty() {
        if (difficulty == null || !ALLOWED_DIFFICULTIES.contains(difficulty.trim())) {
            return "medium";
        }
        return difficulty.trim();
    }
}
