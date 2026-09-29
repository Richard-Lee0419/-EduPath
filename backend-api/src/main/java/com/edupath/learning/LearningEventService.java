package com.edupath.learning;

import com.edupath.auth.AuthContext;
import com.edupath.profile.ProfileService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningEventService {

    private final LearningEventRepository learningEventRepository;
    private final ProfileService profileService;

    public LearningEventService(
            LearningEventRepository learningEventRepository, ProfileService profileService) {
        this.learningEventRepository = learningEventRepository;
        this.profileService = profileService;
    }

    @Transactional
    public LearningUpdateResult record(List<LearningEventCommand> commands) {
        var principal = AuthContext.requirePrincipal();
        String studentId = AuthContext.currentStudentId();
        List<String> eventIds = new ArrayList<>();
        List<MasteryUpdate> masteryUpdates = new ArrayList<>();
        int duplicates = 0;
        for (LearningEventCommand command : commands == null ? List.<LearningEventCommand>of() : commands) {
            if (command.knowledgePoint() == null || command.knowledgePoint().isBlank()) {
                continue;
            }
            String eventId = "learn_" + UUID.randomUUID().toString().replace("-", "");
            boolean inserted = learningEventRepository.insert(
                    command, eventId, studentId, principal.userId());
            if (!inserted) {
                duplicates++;
                continue;
            }
            eventIds.add(eventId);
            if (command.evidenceWeight() > 0) {
                masteryUpdates.add(learningEventRepository.applyBehaviorEvidence(
                        studentId,
                        command.knowledgePoint(),
                        command.evidenceWeight(),
                        command.evidenceScore(),
                        command.eventType()));
            }
        }
        Map<String, Object> profileUpdate = masteryUpdates.isEmpty()
                ? Map.of("updated", false)
                : profileService.updateAfterLearningEvents(
                        masteryUpdates.stream()
                                .map(item -> new ProfileService.MasterySignal(
                                        item.knowledgePoint(), item.masteryScore(), item.eventType()))
                                .toList(),
                        eventIds.get(0));
        return new LearningUpdateResult(eventIds.size(), duplicates, eventIds, masteryUpdates, profileUpdate);
    }

    public LearningEventPage events(int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 50));
        return new LearningEventPage(
                learningEventRepository.events(
                        AuthContext.currentStudentId(), normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize);
    }

    public List<Integer> completedPathDays(String studentId, String pathId) {
        if (pathId == null || pathId.isBlank()) {
            return List.of();
        }
        return learningEventRepository.completedPathDays(studentId, pathId);
    }

    public record LearningEventCommand(
            String eventType,
            int courseId,
            String knowledgePoint,
            String sourceType,
            String sourceId,
            String action,
            Integer progressPercent,
            Integer sequenceNo,
            int evidenceWeight,
            int evidenceScore,
            String idempotencyKey,
            Map<String, Object> metadata) {}

    public record MasteryUpdate(
            String knowledgePoint,
            int previousScore,
            int masteryScore,
            int quizAttempts,
            int behaviorWeight,
            String eventType) {}

    public record LearningUpdateResult(
            int recordedEvents,
            int duplicateEvents,
            List<String> eventIds,
            List<MasteryUpdate> masteryUpdates,
            Map<String, Object> profileUpdate) {}

    public record LearningEventPage(
            List<LearningEventRepository.LearningEventRow> items, int page, int size) {}
}
