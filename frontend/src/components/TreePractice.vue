<script setup lang="ts">
import { ref } from 'vue';
import { getTreePractice, submitTreePractice, type TreeExercise } from '../api/treePractice';
import SpecialtyPractice from './SpecialtyPractice.vue';

const answer = ref('');
const orderNames = { preorder: '前序', inorder: '中序', postorder: '后序' };

function readAnswer() {
  const value = answer.value.trim().replaceAll('->', '→');
  if (!/^[A-Za-z]+(?:[\s,，、→]+[A-Za-z]+)*$/.test(value)) return null;
  const sequence = value.toUpperCase().match(/[A-Z]/g) ?? [];
  return sequence.length <= 24 ? sequence : null;
}

function describeExercise(exercise: TreeExercise) {
  return `${orderNames[exercise.order]}遍历 · 根节点 ${exercise.nodes[0]?.id}`;
}

function describeAnswer(sequence: string[]) {
  return sequence.join(' → ');
}
</script>

<template>
  <SpecialtyPractice
    topic="tree"
    course="算法与数据结构"
    title="二叉树递归遍历专项练习"
    source-title="二叉树递归遍历与错因诊断"
    validation-message="请输入 1 到 24 个节点字母，可用空格、逗号或箭头分隔"
    :load-state="getTreePractice"
    :submit-attempt="submitTreePractice"
    :read-answer="readAnswer"
    :describe-exercise="describeExercise"
    :describe-answer="describeAnswer"
    @reset-answer="answer = ''"
  >
    <template #exercise="{ exercise, submitting }">
      <p>根节点为 <strong>{{ exercise.nodes[0]?.id }}</strong>。请按<strong>{{ orderNames[exercise.order] }}遍历</strong>填写节点的访问顺序。</p>
      <table class="tree-structure" aria-label="二叉树的左右孩子">
        <thead><tr><th scope="col">节点</th><th scope="col">左孩子</th><th scope="col">右孩子</th></tr></thead>
        <tbody>
          <tr v-for="node in exercise.nodes" :key="node.id">
            <th scope="row">{{ node.id }}</th>
            <td>{{ node.left ?? '无' }}</td>
            <td>{{ node.right ?? '无' }}</td>
          </tr>
        </tbody>
      </table>
      <label class="sequence-label" for="tree-sequence">访问序列</label>
      <input id="tree-sequence" v-model="answer" class="sequence-input" type="text" maxlength="120"
        autocomplete="off" spellcheck="false" autocapitalize="characters" required :disabled="submitting"
        aria-describedby="tree-sequence-help" />
      <p id="tree-sequence-help" class="sequence-help">节点用单个字母表示，可用空格、逗号或箭头分隔。</p>
    </template>
  </SpecialtyPractice>
</template>

<style scoped>
.tree-structure {
  width: min(100%, 22rem);
  margin: 14px 0;
  border-collapse: collapse;
  text-align: left;
  font-size: 0.9rem;
}

.tree-structure th,
.tree-structure td {
  padding: 4px 12px;
  border-bottom: 1px solid #d9e7e2;
}

.tree-structure thead { background: #f3f8f5; }
.sequence-label { display: block; font-weight: 600; }

.sequence-input {
  display: block;
  width: min(100%, 22rem);
  margin: 4px 0;
  padding: 8px 10px;
  border: 1px solid #8caaa0;
  border-radius: 8px;
  color: inherit;
  font: inherit;
}

.sequence-help { margin: 4px 0 14px; color: #516963; font-size: 0.9rem; }
</style>
