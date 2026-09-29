package com.edupath.agenttask;

import com.edupath.auth.AuthContext;
import com.edupath.auth.JwtService;
import com.edupath.common.JsonCodec;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentTaskService {

    private static final Set<String> TASK_STATUSES = Set.of("pending", "running", "success", "failed", "cancelled");
    private static final Set<String> RETRYABLE_OPERATIONS = Set.of(
            "resource_task", "profile_chat", "path_generate", "tutor_chat", "kb_reindex", "quality_regression");

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final AsyncTaskExecutor taskExecutor;
    private final AgentTaskRepository agentTaskRepository;
    private final JsonCodec jsonCodec;
    private final long delayMillis;

    public AgentTaskService(
            AsyncTaskExecutor taskExecutor,
            AgentTaskRepository agentTaskRepository,
            JsonCodec jsonCodec,
            @Value("${edupath.demo-task-delay-millis:120}") long delayMillis) {
        this.taskExecutor = taskExecutor;
        this.agentTaskRepository = agentTaskRepository;
        this.jsonCodec = jsonCodec;
        this.delayMillis = delayMillis;
    }

    @PostConstruct
    void recoverTasks() {
        agentTaskRepository.markStaleRunningTasksFailed();
    }

    public TaskCreatedResponse createWorkflowTask(
            String domain, List<AgentStepPlan> plans, Supplier<Object> resultSupplier) {
        return createWorkflowTask(domain, plans, null, null, resultSupplier);
    }

    public TaskCreatedResponse createWorkflowTask(
            String domain,
            List<AgentStepPlan> plans,
            String operationType,
            Object requestPayload,
            Supplier<Object> resultSupplier) {
        return createTask(domain, plans, operationType, requestPayload, state -> runWorkflow(state, plans, resultSupplier));
    }

    public TaskCreatedResponse createObservableWorkflowTask(
            String domain,
            List<AgentStepPlan> plans,
            String operationType,
            Object requestPayload,
            WorkflowExecutor executor) {
        return createTask(domain, plans, operationType, requestPayload, state -> runObservableWorkflow(state, plans, executor));
    }

    public synchronized IdempotentTaskCreatedResponse createIdempotentObservableWorkflowTask(
            String domain,
            String idempotencyKey,
            List<AgentStepPlan> plans,
            String operationType,
            Object requestPayload,
            WorkflowExecutor executor) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        String deterministicSuffix = UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        String taskId = "task_" + domain + "_" + deterministicSuffix;
        if (agentTaskRepository.findSnapshot(taskId).isPresent()) {
            return new IdempotentTaskCreatedResponse(taskId, true);
        }
        return createTaskWithId(
                taskId,
                domain,
                plans,
                operationType,
                requestPayload,
                state -> runObservableWorkflow(state, plans, executor));
    }

    private TaskCreatedResponse createTask(
            String domain,
            List<AgentStepPlan> plans,
            String operationType,
            Object requestPayload,
            WorkflowRunner runner) {
        return createTaskWithId(null, domain, plans, operationType, requestPayload, runner).asRegularResponse();
    }

    private IdempotentTaskCreatedResponse createTaskWithId(
            String requestedTaskId,
            String domain,
            List<AgentStepPlan> plans,
            String operationType,
            Object requestPayload,
            WorkflowRunner runner) {
        String taskId = requestedTaskId == null
                ? "task_" + domain + "_" + UUID.randomUUID().toString().replace("-", "")
                : requestedTaskId;
        JwtService.AuthPrincipal principal = AuthContext.current();
        TaskState state = new TaskState(taskId, plans);
        tasks.put(taskId, state);
        try {
            agentTaskRepository.createTask(
                    taskId,
                    domain,
                    "pending",
                    0,
                    operationType,
                    requestPayload == null ? null : jsonCodec.toJson(requestPayload),
                    principal == null ? null : principal.userId(),
                    principal == null ? null : principal.username(),
                    plans);
        } catch (DuplicateKeyException duplicate) {
            tasks.remove(taskId, state);
            requireTaskAccess(taskId);
            return new IdempotentTaskCreatedResponse(taskId, true);
        }
        Future<?> future = taskExecutor.submit(() -> {
            if (principal != null) {
                AuthContext.set(principal);
            }
            try {
                runner.run(state);
            } finally {
                AuthContext.clear();
                futures.remove(taskId);
            }
        });
        futures.put(taskId, future);
        return new IdempotentTaskCreatedResponse(taskId, false);
    }

    public AgentTaskSnapshot getTask(String taskId) {
        requireTaskAccess(taskId);
        TaskState state = tasks.get(taskId);
        if (state == null) {
            return agentTaskRepository
                    .findSnapshot(taskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
        }
        return state.snapshot();
    }

    public TaskCreatedResponse cancelTask(String taskId) {
        requireTaskAccess(taskId);
        TaskState state = tasks.get(taskId);
        if (state != null) {
            if (isTerminal(state.snapshot().status())) {
                return new TaskCreatedResponse(taskId);
            }
            state.markCancelled();
            Future<?> future = futures.remove(taskId);
            if (future != null) {
                future.cancel(true);
            }
            persistSnapshot(state.snapshot());
            return new TaskCreatedResponse(taskId);
        }
        AgentTaskSnapshot snapshot = getTask(taskId);
        if (!isTerminal(snapshot.status())) {
            agentTaskRepository.updateTask(taskId, "cancelled", snapshot.progress(), null, snapshot.result(), null);
        }
        return new TaskCreatedResponse(taskId);
    }

    public AgentTaskRepository.TaskMetadata getTaskMetadata(String taskId) {
        AgentTaskRepository.TaskMetadata metadata = agentTaskRepository
                .findMetadata(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
        requireTaskAccess(metadata);
        return metadata;
    }

    public TaskListResponse listTasks(String status, int page, int size) {
        JwtService.AuthPrincipal principal = AuthContext.requirePrincipal();
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toLowerCase();
        if (normalizedStatus != null && !TASK_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务状态: " + status);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        int offset = (safePage - 1) * safeSize;
        List<TaskListItem> items = agentTaskRepository
                .listTasks(principal.userId(), normalizedStatus, safeSize, offset)
                .stream()
                .map(item -> new TaskListItem(
                        item.taskId(),
                        item.domain(),
                        item.operationType(),
                        item.status(),
                        item.progress(),
                        item.currentAgent(),
                        item.errorMessage(),
                        item.actorUsername(),
                        RETRYABLE_OPERATIONS.contains(item.operationType()),
                        item.createdAt(),
                        item.updatedAt()))
                .toList();
        long total = agentTaskRepository.countTasks(principal.userId(), normalizedStatus);
        return new TaskListResponse(items, total, safePage, safeSize);
    }

    public void streamTask(String taskId, SseEmitter emitter) {
        getTask(taskId);
        JwtService.AuthPrincipal principal = AuthContext.requirePrincipal();
        taskExecutor.execute(() -> {
            AuthContext.set(principal);
            Set<String> sentSteps = new HashSet<>();
            try {
                while (true) {
                    AgentTaskSnapshot snapshot = getTask(taskId);
                    emitter.send(SseEmitter.event().name("task_status").data(snapshot));
                    for (AgentStepDto step : snapshot.steps()) {
                        if (!"pending".equals(step.status())) {
                            String key = step.agent() + ":" + step.status();
                            if (sentSteps.add(key)) {
                                emitter.send(SseEmitter.event().name("agent_step").data(step));
                            }
                        }
                    }
                    if (isTerminal(snapshot.status())) {
                        emitter.send(SseEmitter.event().name("done").data(Map.of("task_id", taskId)));
                        emitter.complete();
                        return;
                    }
                    pause(Math.max(150, delayMillis));
                }
            } catch (IOException | IllegalStateException exception) {
                emitter.completeWithError(exception);
            } finally {
                AuthContext.clear();
            }
        });
    }

    private void requireTaskAccess(String taskId) {
        AgentTaskRepository.TaskMetadata metadata = agentTaskRepository
                .findMetadata(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
        requireTaskAccess(metadata);
    }

    private void requireTaskAccess(AgentTaskRepository.TaskMetadata metadata) {
        JwtService.AuthPrincipal principal = AuthContext.requirePrincipal();
        if ("admin".equals(principal.role()) || metadata.actorUserId() == principal.userId()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该任务");
    }

    private void runWorkflow(TaskState state, List<AgentStepPlan> plans, Supplier<Object> resultSupplier) {
        try {
            state.markStarted();
            persistSnapshot(state.snapshot());
            if (plans.isEmpty()) {
                state.markResult(resultSupplier.get());
                state.markCompleted();
                persistSnapshot(state.snapshot());
                return;
            }
            for (int i = 0; i < plans.size(); i++) {
                if (state.isCancelled()) {
                    persistSnapshot(state.snapshot());
                    return;
                }
                AgentStepPlan plan = plans.get(i);
                int runningProgress = Math.min(90, 8 + (i * 80 / plans.size()));
                state.markStepRunning(i, plan.runningMessage(), runningProgress);
                agentTaskRepository.updateStep(state.taskId, i, "running", plan.runningMessage());
                persistSnapshot(state.snapshot());
                pause(delayMillis);
                if (state.isCancelled()) {
                    persistSnapshot(state.snapshot());
                    return;
                }
                int successProgress = Math.min(94, 12 + ((i + 1) * 80 / plans.size()));
                state.markStepSuccess(i, plan.successMessage(), successProgress);
                agentTaskRepository.updateStep(state.taskId, i, "success", plan.successMessage());
                persistSnapshot(state.snapshot());
            }
            state.markResult(resultSupplier.get());
            state.markCompleted();
            persistSnapshot(state.snapshot());
        } catch (Exception exception) {
            state.markFailed(exception.getMessage());
            AgentTaskSnapshot snapshot = state.snapshot();
            for (int i = 0; i < snapshot.steps().size(); i++) {
                AgentStepDto step = snapshot.steps().get(i);
                agentTaskRepository.updateStep(snapshot.taskId(), i, step.status(), step.message());
            }
            persistSnapshot(snapshot);
        }
    }

    private void runObservableWorkflow(TaskState state, List<AgentStepPlan> plans, WorkflowExecutor executor) {
        try {
            state.markStarted();
            persistSnapshot(state.snapshot());
            TaskProgress progress = new TaskProgress(state, plans);
            Object result = executor.execute(progress);
            progress.completePendingSteps();
            state.markResult(result);
            state.markCompleted();
            persistSnapshot(state.snapshot());
        } catch (Exception exception) {
            state.markFailed(exception.getMessage());
            AgentTaskSnapshot snapshot = state.snapshot();
            for (int i = 0; i < snapshot.steps().size(); i++) {
                AgentStepDto step = snapshot.steps().get(i);
                agentTaskRepository.updateStep(snapshot.taskId(), i, step.status(), step.message());
            }
            persistSnapshot(snapshot);
        }
    }

    private void persistSnapshot(AgentTaskSnapshot snapshot) {
        agentTaskRepository.updateTask(
                snapshot.taskId(),
                snapshot.status(),
                snapshot.progress(),
                snapshot.currentAgent(),
                snapshot.result(),
                snapshot.errorMessage());
    }

    private boolean isTerminal(String status) {
        return "success".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private void pause(long millis) {
        try {
            Thread.sleep(Math.max(millis, 0));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务执行被中断", exception);
        }
    }

    public final class TaskProgress {
        private final TaskState state;
        private final List<AgentStepPlan> plans;

        private TaskProgress(TaskState state, List<AgentStepPlan> plans) {
            this.state = state;
            this.plans = plans;
        }

        public void running(String agent, String message, int progress) {
            int index = findStepIndex(agent);
            if (index < 0 || state.isCancelled()) {
                return;
            }
            state.markStepRunning(index, message, progress);
            agentTaskRepository.updateStep(state.taskId, index, "running", message);
            persistSnapshot(state.snapshot());
        }

        public void success(String agent, String message, int progress) {
            int index = findStepIndex(agent);
            if (index < 0 || state.isCancelled()) {
                return;
            }
            state.markStepSuccess(index, message, progress);
            agentTaskRepository.updateStep(state.taskId, index, "success", message);
            persistSnapshot(state.snapshot());
        }

        public String taskId() {
            return state.taskId;
        }

        private void completePendingSteps() {
            AgentTaskSnapshot snapshot = state.snapshot();
            for (int i = 0; i < snapshot.steps().size(); i++) {
                AgentStepDto step = snapshot.steps().get(i);
                if ("pending".equals(step.status()) || "running".equals(step.status())) {
                    String message = i < plans.size() ? plans.get(i).successMessage() : "已完成";
                    int progress = Math.min(98, Math.max(snapshot.progress(), 12 + ((i + 1) * 80 / Math.max(1, plans.size()))));
                    state.markStepSuccess(i, message, progress);
                    agentTaskRepository.updateStep(state.taskId, i, "success", message);
                    persistSnapshot(state.snapshot());
                }
            }
        }

        private int findStepIndex(String agent) {
            AgentTaskSnapshot snapshot = state.snapshot();
            for (int i = 0; i < snapshot.steps().size(); i++) {
                AgentStepDto step = snapshot.steps().get(i);
                if (step.agent().equals(agent) && !"success".equals(step.status())) {
                    return i;
                }
            }
            for (int i = 0; i < snapshot.steps().size(); i++) {
                if (snapshot.steps().get(i).agent().equals(agent)) {
                    return i;
                }
            }
            for (int i = 0; i < snapshot.steps().size(); i++) {
                String status = snapshot.steps().get(i).status();
                if ("pending".equals(status) || "running".equals(status)) {
                    return i;
                }
            }
            return -1;
        }
    }

    @FunctionalInterface
    public interface WorkflowExecutor {
        Object execute(TaskProgress progress);
    }

    @FunctionalInterface
    private interface WorkflowRunner {
        void run(TaskState state);
    }

    private static final class TaskState {
        private final String taskId;
        private final List<AgentStepState> steps;
        private final OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        private String status = "pending";
        private int progress = 0;
        private String currentAgent;
        private Object result;
        private String errorMessage;
        private boolean cancelled;
        private OffsetDateTime updatedAt = createdAt;

        private TaskState(String taskId, List<AgentStepPlan> plans) {
            this.taskId = taskId;
            this.steps = plans.stream()
                    .map(plan -> new AgentStepState(plan.agent(), "pending", "等待执行"))
                    .toList();
        }

        private synchronized void markStarted() {
            status = "running";
            progress = 3;
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized void markStepRunning(int index, String message, int newProgress) {
            AgentStepState step = steps.get(index);
            step.status = "running";
            step.message = message;
            currentAgent = step.agent;
            status = "running";
            progress = Math.max(progress, newProgress);
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized void markStepSuccess(int index, String message, int newProgress) {
            AgentStepState step = steps.get(index);
            step.status = "success";
            step.message = message;
            progress = Math.max(progress, newProgress);
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized void markResult(Object result) {
            this.result = result;
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized void markCompleted() {
            if (cancelled) {
                return;
            }
            status = "success";
            progress = 100;
            currentAgent = null;
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized void markFailed(String message) {
            if (cancelled) {
                return;
            }
            status = "failed";
            errorMessage = message == null ? "任务执行失败" : message;
            progress = Math.max(progress, 1);
            if (currentAgent != null) {
                for (AgentStepState step : steps) {
                    if (currentAgent.equals(step.agent) && !"success".equals(step.status)) {
                        step.status = "failed";
                        step.message = errorMessage;
                        break;
                    }
                }
            }
            if (steps.stream().noneMatch(step -> "failed".equals(step.status))) {
                for (int i = steps.size() - 1; i >= 0; i--) {
                    AgentStepState step = steps.get(i);
                    if ("running".equals(step.status) || "pending".equals(step.status) || "success".equals(step.status)) {
                        step.status = "failed";
                        step.message = errorMessage;
                        break;
                    }
                }
            }
            currentAgent = null;
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized void markCancelled() {
            cancelled = true;
            status = "cancelled";
            currentAgent = null;
            errorMessage = null;
            for (AgentStepState step : steps) {
                if ("pending".equals(step.status) || "running".equals(step.status)) {
                    step.status = "cancelled";
                    step.message = "任务已取消";
                }
            }
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        }

        private synchronized boolean isCancelled() {
            return cancelled;
        }

        private synchronized AgentTaskSnapshot snapshot() {
            List<AgentStepDto> stepDtos = new ArrayList<>();
            for (AgentStepState step : steps) {
                stepDtos.add(new AgentStepDto(step.agent, step.status, step.message));
            }
            return new AgentTaskSnapshot(
                    taskId, status, progress, currentAgent, stepDtos, result, errorMessage, createdAt, updatedAt);
        }
    }

    private static final class AgentStepState {
        private final String agent;
        private String status;
        private String message;

        private AgentStepState(String agent, String status, String message) {
            this.agent = agent;
            this.status = status;
            this.message = message;
        }
    }

    public record TaskCreatedResponse(String taskId) {}

    public record TaskListResponse(List<TaskListItem> items, long total, int page, int size) {}

    public record TaskListItem(
            String taskId,
            String domain,
            String operationType,
            String status,
            int progress,
            String currentAgent,
            String errorMessage,
            String actorUsername,
            boolean retryable,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record IdempotentTaskCreatedResponse(String taskId, boolean reused) {
        TaskCreatedResponse asRegularResponse() {
            return new TaskCreatedResponse(taskId);
        }
    }

    public record AgentStepPlan(String agent, String runningMessage, String successMessage) {}

    public record AgentStepDto(String agent, String status, String message) {}

    public record AgentTaskSnapshot(
            String taskId,
            String status,
            int progress,
            String currentAgent,
            List<AgentStepDto> steps,
            Object result,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
