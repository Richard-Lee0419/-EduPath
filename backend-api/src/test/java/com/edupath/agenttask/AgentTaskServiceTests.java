package com.edupath.agenttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupath.common.JsonCodec;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

class AgentTaskServiceTests {

    @Test
    void shouldReuseDeterministicTaskForSameIdempotencyKey() {
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        AgentTaskRepository repository = mock(AgentTaskRepository.class);
        JsonCodec jsonCodec = mock(JsonCodec.class);
        @SuppressWarnings("unchecked")
        Future<Object> future = mock(Future.class);
        doReturn(future).when(executor).submit(any(Runnable.class));
        when(jsonCodec.toJson(any())).thenReturn("{}");

        AgentTaskService.AgentTaskSnapshot existing = new AgentTaskService.AgentTaskSnapshot(
                "placeholder",
                "running",
                20,
                "BehaviorSignalAgent",
                List.of(),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now());
        when(repository.findSnapshot(anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        AgentTaskService service = new AgentTaskService(executor, repository, jsonCodec, 0);
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan("BehaviorSignalAgent", "running", "success"));

        AgentTaskService.IdempotentTaskCreatedResponse first =
                service.createIdempotentObservableWorkflowTask(
                        "path", "student:path:v1:signal", plans, "path_behavior_replan", null, progress -> null);
        AgentTaskService.IdempotentTaskCreatedResponse second =
                service.createIdempotentObservableWorkflowTask(
                        "path", "student:path:v1:signal", plans, "path_behavior_replan", null, progress -> null);

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.taskId()).isEqualTo(first.taskId());
        verify(repository, times(1)).createTask(
                anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any());
        verify(executor, times(1)).submit(any(Runnable.class));
    }
}
