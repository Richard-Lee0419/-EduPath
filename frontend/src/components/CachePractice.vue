<script setup lang="ts">
import { ref } from 'vue';
import { getCachePractice, submitCachePractice, type CacheBits, type CacheExercise } from '../api/cachePractice';
import SpecialtyPractice from './SpecialtyPractice.vue';

const answer = ref<Record<keyof CacheBits, number | string>>({ tag: '', index: '', offset: '' });
const fields = [
  { key: 'tag', label: 'Tag' },
  { key: 'index', label: 'Index' },
  { key: 'offset', label: 'Offset' },
] as const;

function reset() {
  answer.value = { tag: '', index: '', offset: '' };
}

function readAnswer() {
  const { tag, index, offset } = answer.value;
  if ([tag, index, offset].some((value) => value === '' || !Number.isInteger(Number(value))
      || Number(value) < 0 || Number(value) > 128)) {
    return null;
  }
  return { tag: Number(tag), index: Number(index), offset: Number(offset) };
}

function describeExercise(exercise: CacheExercise) {
  return `${exercise.ways} 路组相联 · ${exercise.block_bytes} B 块`;
}

function describeAnswer(bits: CacheBits) {
  return fields.map(({ key, label }) => `${label} ${bits[key]} 位`).join(' · ');
}
</script>

<template>
  <SpecialtyPractice
    topic="cache"
    course="计算机组成原理"
    title="Cache 地址拆分专项练习"
    source-title="Cache 地址拆分与错因诊断"
    validation-message="请输入 0 到 128 的整数"
    :load-state="getCachePractice"
    :submit-attempt="submitCachePractice"
    :read-answer="readAnswer"
    :describe-exercise="describeExercise"
    :describe-answer="describeAnswer"
    @reset-answer="reset"
  >
    <template #exercise="{ exercise, submitting }">
      <p>
        {{ exercise.address_bits }} 位地址，按字节寻址。Cache 数据容量 {{ exercise.capacity_bytes }} B，
        块大小 {{ exercise.block_bytes }} B，{{ exercise.ways }} 路组相联。
        请分别填写 Tag、Index、Offset 所需的位数。
      </p>
      <div class="practice-fields">
        <label v-for="{ key, label } in fields" :key="key">
          {{ label }} 位数
          <input v-model="answer[key]" type="number" inputmode="numeric" min="0" max="128" step="1"
            required :disabled="submitting" />
        </label>
      </div>
    </template>
  </SpecialtyPractice>
</template>

<style scoped>
.practice-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 9rem));
  gap: 12px;
  margin: 14px 0;
}

.practice-fields label {
  display: grid;
  gap: 5px;
  font-size: 0.875rem;
  font-weight: 600;
}

.practice-fields input {
  min-width: 0;
  width: 100%;
  padding: 8px;
  border: 1px solid #8caaa0;
  border-radius: 8px;
  color: inherit;
  font: inherit;
}
</style>
