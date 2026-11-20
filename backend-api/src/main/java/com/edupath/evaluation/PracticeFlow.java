package com.edupath.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PracticeFlow<E extends PracticeFlow.Exercise, A> {

    private final E initial;
    private final BiFunction<Feedback<A>, Set<String>, FollowUp<E>> selectFollowUp;

    PracticeFlow(E initial, BiFunction<Feedback<A>, Set<String>, FollowUp<E>> selectFollowUp) {
        this.initial = initial;
        this.selectFollowUp = selectFollowUp;
    }

    State<E, A> snapshot(Map<String, Attempt<E, A>> attempts) {
        Attempt<E, A> first = attempts.get(initial.id());
        if (first == null) {
            return new State<>("initial", initial, null, null, null);
        }
        FollowUp<E> followUp = selectFollowUp.apply(first.feedback(), attempts.keySet());
        Attempt<E, A> retest = attempts.get(followUp.exercise().id());
        if (retest == null) {
            return new State<>("retest", followUp.exercise(), first, null, followUp.focus());
        }
        return new State<>("complete", null, first, retest, followUp.focus());
    }

    State<E, A> submit(
            String exerciseId, A answer, Supplier<Map<String, Attempt<E, A>>> loadAttempts,
            Function<E, Feedback<A>> grade, BiConsumer<E, Feedback<A>> insert) {
        Map<String, Attempt<E, A>> attempts = loadAttempts.get();
        Attempt<E, A> existing = attempts.get(exerciseId);
        if (existing != null) {
            ensureSameAnswer(existing, answer);
            return snapshot(attempts);
        }

        E exercise = snapshot(attempts).nextExercise();
        if (exercise == null || !exercise.id().equals(exerciseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请刷新练习并提交当前题目");
        }
        Feedback<A> feedback = grade.apply(exercise);
        try {
            insert.accept(exercise, feedback);
        } catch (DuplicateKeyException concurrent) {
            Map<String, Attempt<E, A>> updated = loadAttempts.get();
            Attempt<E, A> saved = updated.get(exerciseId);
            if (saved == null) {
                throw concurrent;
            }
            ensureSameAnswer(saved, answer);
            return snapshot(updated);
        }
        return snapshot(loadAttempts.get());
    }

    private void ensureSameAnswer(Attempt<E, A> saved, A answer) {
        if (!saved.answer().equals(answer)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本题已有作答，请重新加载查看已保存结果");
        }
    }

    public interface Exercise {
        String id();
    }

    record FollowUp<E>(E exercise, String focus) {}

    public record Citation(String marker, String document, String section, String excerpt, String scope,
                           String sourceVersion) {}

    public record State<E, A>(String stage, E nextExercise, Attempt<E, A> initial, Attempt<E, A> retest, String focus) {}

    public record Attempt<E, A>(E exercise, A answer, Feedback<A> feedback, Instant submittedAt) {}

    public record Provenance(String source, String requestId, String executionId) {}

    public record Feedback<A>(boolean correct, A expected, String mistake, String explanation, Citation citation,
                              Provenance provenance) {
        public Feedback(boolean correct, A expected, String mistake, String explanation, Citation citation) {
            this(correct, expected, mistake, explanation, citation, null);
        }
    }
}
