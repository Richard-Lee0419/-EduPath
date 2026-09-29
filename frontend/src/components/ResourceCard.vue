<script setup lang="ts">
import type { ResourceCard } from '../types';

interface ResourceCardMeta {
  displayType?: string;
  fitReason?: string;
  difficultyLabel?: string;
  generatedBy?: string;
  safetyStatus?: string;
  preview?: string;
  actionLabel?: string;
  sourcePoint?: string;
}

defineProps<{
  resource: ResourceCard & ResourceCardMeta;
}>();

const typeLabels: Record<ResourceCard['type'], string> = {
  lecture: '个性化讲义',
  mindmap: '思维导图',
  quiz: '分层练习题',
  codelab: '代码实验',
  animation_script: '动画脚本',
  flowchart: '流程图',
  reading: '拓展阅读',
};

const agentLabels: Record<ResourceCard['type'], string> = {
  lecture: 'LectureAgent',
  mindmap: 'VisualizationAgent',
  quiz: 'QuizAgent',
  codelab: 'CodeAgent',
  animation_script: 'VisualizationAgent',
  flowchart: 'PlannerAgent',
  reading: 'KnowledgeAgent',
};
</script>

<template>
  <article class="resource-card resource-cover" :class="`accent-${resource.accent}`">
    <div class="resource-card__top">
      <span>{{ resource.displayType ?? typeLabels[resource.type] }}</span>
      <small v-if="resource.minutes">{{ resource.minutes }} min</small>
      <small v-if="resource.qualityScore !== undefined" class="quality-badge">
        质量 {{ resource.qualityGrade }} · {{ Math.round(resource.qualityScore) }}
      </small>
    </div>

    <h3>{{ resource.title }}</h3>
    <p>{{ resource.subtitle }}</p>

    <div v-if="resource.fitReason" class="resource-card__reason">
      <span>为什么适合当前学生</span>
      <strong>{{ resource.fitReason }}</strong>
    </div>

    <div v-if="resource.preview || resource.sourcePoint" class="resource-card__preview">
      {{ resource.preview ?? resource.sourcePoint }}
    </div>

    <div class="tag-row">
      <span v-for="tag in resource.tags" :key="tag">{{ tag }}</span>
    </div>

    <div class="resource-card__meta">
      <div v-if="resource.difficultyLabel">
        <span>难度</span>
        <strong>{{ resource.difficultyLabel }}</strong>
      </div>
      <div>
        <span>生成 Agent</span>
        <strong>{{ resource.generatedBy ?? agentLabels[resource.type] }}</strong>
      </div>
      <div v-if="resource.safetyStatus || resource.confidence">
        <span>安全审查</span>
        <strong>{{ resource.safetyStatus ?? `SafetyAgent ${Math.round((resource.confidence ?? 0) * 100)}%` }}</strong>
      </div>
    </div>

    <div class="resource-card__actions">
      <button class="text-action" type="button">{{ resource.actionLabel ?? '查看资源' }}</button>
      <span v-if="resource.confidence">{{ Math.round(resource.confidence * 100) }}% 置信</span>
    </div>
  </article>
</template>
