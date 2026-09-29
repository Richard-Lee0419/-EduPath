<script setup lang="ts">
import { computed } from 'vue';
import type { AgentStep } from '../types';

const props = defineProps<{
  steps: AgentStep[];
  taskId?: string;
}>();

const roleMap: Record<string, string> = {
  ProfileAgent: '画像理解',
  KnowledgeAgent: '课程证据',
  PlannerAgent: '资源规划',
  ResourcePlannerAgent: '资源规划',
  'LectureAgent · QuizAgent · VisualizationAgent · CodeAgent': '并行生成',
  LectureAgent: '讲义生成',
  QuizAgent: '题库生成',
  ScoringEngine: '确定性判分',
  EvaluationAgent: '学习评估',
  SafetyAgent: '安全审查',
  ResourceAgent: '资源输出',
};

const statusLabels = {
  pending: '等待',
  running: '运行中',
  success: '完成',
  failed: '失败',
  cancelled: '已取消',
};

const normalizedSteps = computed(() =>
  props.steps.map((step, index) => ({
    ...step,
    role: roleMap[step.agent] ?? 'Agent 协作',
    statusLabel: statusLabels[step.status],
    order: `${index + 1}`.padStart(2, '0'),
  })),
);
</script>

<template>
  <section class="workflow-panel agent-flow-panel">
    <div class="agent-flow-header">
      <div>
        <span>Agent Orchestration Rail</span>
        <h2>资源生成 Agent 编排轨道</h2>
        <p>画像、知识检索、资源规划、并行生成和安全审查在同一条轨道上协同推进。</p>
      </div>
      <strong>{{ props.taskId || '等待 task_id' }}</strong>
    </div>

    <div class="agent-flow-rail">
      <article
        v-for="(step, index) in normalizedSteps"
        :key="`${step.agent}-${index}`"
        class="agent-flow-step"
        :class="step.status"
        :style="{ '--step-index': index }"
      >
        <div class="agent-flow-node">
          <span>{{ step.order }}</span>
        </div>
        <div class="agent-flow-copy">
          <div class="agent-flow-meta">
            <span>{{ step.role }}</span>
            <small>{{ step.statusLabel }}</small>
          </div>
          <strong>{{ step.title }}</strong>
          <em>{{ step.agent }}</em>
          <p>{{ step.message }}</p>
        </div>
      </article>
    </div>
  </section>
</template>
