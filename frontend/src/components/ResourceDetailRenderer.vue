<script setup lang="ts">
/**
 * W03 功能型类型渲染器：按 resource_type 渲染五类资源 payload。
 *
 * 原则：
 * - 只做功能渲染，不做视觉 redesign（沿用现有卡片/字号/颜色体系）；
 * - payload 缺失 / malformed / 未知类型 ⇒ 回退 RichContentRenderer 渲染 markdown 正文，
 *   保证历史 plaintext 资源永远可读；
 * - quiz 提交前不显示任何答案（payload 中本就没有答案，服务端在提交接口才返回）；
 * - codelab 明确“无在线执行”，只提供参考预期输出；
 * - animation_script 命名为“动画教学脚本/分镜”，绝不声称“已生成动画视频”。
 */
import { computed, ref, watch } from 'vue';
import RichContentRenderer from './RichContentRenderer.vue';
import DfsLearningMap from './resource/DfsLearningMap.vue';
import {
  parseResourcePayload,
  type QuizPayload,
  type ResourcePayload,
} from '../resource/payload';
import { submitResourceQuiz } from '../api/resource';
import type { ResourceDetail } from '../types/api';

const props = defineProps<{ detail: ResourceDetail }>();
const emit = defineEmits<{ 'quiz-submitted': [] }>();

const payload = computed<ResourcePayload | null>(() =>
  parseResourcePayload(props.detail.resource_type || props.detail.type, props.detail.payload ?? null),
);

const usesFallback = computed(() => payload.value === null);
const isStudentMindmapFallback = computed(() => {
  if (!usesFallback.value || !['mindmap', 'flowchart'].includes(props.detail.resource_type || props.detail.type || '')) return false;
  const searchable = `${props.detail.title || ''} ${props.detail.summary || ''} ${props.detail.content || ''}`;
  return /(?:\bDFS\b|深度优先(?:搜索|遍历)?)/i.test(searchable);
});

// ---------- quiz 交互状态 ----------
const quizAnswers = ref<Record<string, string>>({});
const quizResult = ref<QuizSubmissionView | null>(null);
const quizSubmitting = ref(false);
const quizError = ref('');

interface QuizSubmissionView {
  score: number | null;
  message: string;
  items: Array<{
    questionId: string;
    correct: boolean | null;
    correctAnswer: string;
    explanation: string;
    knowledgePoint: string;
    diagnosticLabel: string;
  }>;
}

const quizPayload = computed(() => (payload.value?.kind === 'quiz' ? (payload.value as QuizPayload) : null));

watch(
  () => props.detail.resource_id || String(props.detail.id),
  () => {
    quizAnswers.value = {};
    quizResult.value = null;
    quizSubmitting.value = false;
    quizError.value = '';
  },
);

const allChoiceAnswered = computed(() => {
  if (!quizPayload.value) return false;
  return quizPayload.value.questions
    .filter((question) => question.questionType === 'single_choice')
    .every((question) => Boolean(quizAnswers.value[question.questionId]));
});

const submitQuiz = async () => {
  if (!quizPayload.value || quizSubmitting.value) return;
  quizSubmitting.value = true;
  quizError.value = '';
  try {
    const resourceId = props.detail.resource_id || String(props.detail.id);
    const result = await submitResourceQuiz(resourceId, quizPayload.value.questions.map((question) => ({
      question_id: question.questionId,
      answer: quizAnswers.value[question.questionId] || '',
    })));
    quizResult.value = {
      score: result.score ?? null,
      message: result.message || '提交完成。',
      items: (result.items || []).map((item) => ({
        questionId: String(item.question_id ?? ''),
        correct: typeof item.correct === 'boolean' ? item.correct : null,
        correctAnswer: String(item.correct_answer ?? ''),
        explanation: String(item.explanation ?? ''),
        knowledgePoint: String(item.knowledge_point ?? ''),
        diagnosticLabel: String(item.diagnostic_label ?? ''),
      })),
    };
    emit('quiz-submitted');
  } catch (error) {
    quizError.value = error instanceof Error ? error.message : '练习提交失败';
  } finally {
    quizSubmitting.value = false;
  }
};

// ---------- codelab copy ----------
const copied = ref(false);
const copyStarterCode = async () => {
  const code = payload.value?.kind === 'codelab' ? payload.value.starterCode : '';
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code);
    copied.value = true;
    setTimeout(() => {
      copied.value = false;
    }, 1600);
  } catch {
    copied.value = false;
  }
};

// ---------- mindmap mermaid（复用 RichContentRenderer 的 mermaid 渲染与文本回退） ----------
const mindmapMarkdown = computed(() => {
  if (payload.value?.kind !== 'mindmap') return '';
  const data = payload.value;
  const sourceText = `${props.detail.title || ''} ${props.detail.summary || ''} ${props.detail.content || ''}`;
  if (/(?:\bDFS\b|深度优先(?:搜索|遍历)?)/i.test(sourceText)) {
    return `\`\`\`mermaid
flowchart LR
  A[① 先看图<br/>认识节点和边] --> B[② 访问节点<br/>标记为已访问]
  B --> C[③ 入栈<br/>记录当前路径]
  C --> D[④ 继续深入<br/>寻找下一个节点]
  D --> E{还有未访问节点吗}
  E -->|有| D
  E -->|没有| F[⑤ 回溯<br/>返回上一个节点]
  F --> G[⑥ 出栈<br/>完成当前节点]
  G --> E
  G --> H[⑦ 得到结果<br/>访问顺序与路径]
  I[可以用来做什么] --> J[路径搜索]
  I --> K[环检测]
  I --> L[连通分量]
\`\`\`\n`;
  }
  if (!data.mermaid) return '';
  return '```mermaid\n' + data.mermaid + '\n```\n';
});


const codelabCodeMarkdown = computed(() => {
  if (payload.value?.kind !== 'codelab') return '';
  return '```' + payload.value.language + '\n' + payload.value.starterCode + '\n```';
});

const mindmapRelations = computed(() => {
  if (payload.value?.kind !== 'mindmap') return [];
  return payload.value.relations.filter((relation) => relation.from && relation.to);
});
</script>

<template>
  <div class="resource-detail-renderer">
    <!-- 回退：历史 plaintext / malformed payload / 未知类型 -->
    <template v-if="usesFallback">
      <template v-if="isStudentMindmapFallback">
        <DfsLearningMap />
        <details class="rdr-fallback"><summary>查看原始知识导图全文</summary><RichContentRenderer :content="detail.content" :content-format="detail.content_format" /></details>
      </template>
      <RichContentRenderer v-else :content="detail.content" :content-format="detail.content_format" />
    </template>

    <!-- lecture / reading -->
    <template v-else-if="payload?.kind === 'lecture'">
      <section v-if="payload.learningObjective.length" class="rdr-block">
        <h3>学习目标</h3>
        <ul><li v-for="item in payload.learningObjective" :key="item">{{ item }}</li></ul>
      </section>
      <section v-if="payload.prerequisites.length" class="rdr-block">
        <h3>前置知识</h3>
        <ul><li v-for="item in payload.prerequisites" :key="item">{{ item }}</li></ul>
      </section>
      <section v-for="section in payload.sections" :key="section.heading" class="rdr-block">
        <h3>{{ section.heading }}</h3>
        <p>{{ section.body }}</p>
      </section>
      <section v-if="payload.workedExamples.length" class="rdr-block">
        <h3>例题演示</h3>
        <article v-for="example in payload.workedExamples" :key="example.title" class="rdr-sub-card">
          <strong>{{ example.title }}</strong>
          <p>题目：{{ example.problem }}</p>
          <p>解法：{{ example.solution }}</p>
        </article>
      </section>
      <section v-if="payload.commonMistakes.length" class="rdr-block">
        <h3>常见错误</h3>
        <ul><li v-for="item in payload.commonMistakes" :key="item">{{ item }}</li></ul>
      </section>
      <section v-if="payload.selfCheck.length" class="rdr-block">
        <h3>自检问题</h3>
        <article v-for="item in payload.selfCheck" :key="item.question" class="rdr-sub-card">
          <strong>{{ item.question }}</strong>
          <p v-if="item.hint">提示：{{ item.hint }}</p>
        </article>
      </section>
      <section v-if="payload.evidenceRefs.length" class="rdr-block">
        <h3>证据引用</h3>
        <p class="rdr-muted">{{ payload.evidenceRefs.join('、') }}</p>
      </section>
      <details class="rdr-fallback"><summary>查看原始讲义全文</summary><RichContentRenderer :content="detail.content" /></details>
    </template>

    <!-- mindmap / flowchart -->
    <template v-else-if="payload?.kind === 'mindmap'">
      <DfsLearningMap v-if="/(?:\bDFS\b|深度优先(?:搜索|遍历)?)/i.test(`${detail.title} ${detail.summary} ${detail.content}`)" />
      <template v-else>
      <p class="rdr-muted">{{ payload.centralConcept }} 的结构关系图（Mermaid 渲染失败时保留下方文本树，不影响阅读）。</p>
      <section v-if="mindmapMarkdown" class="rdr-block">
        <RichContentRenderer :content="mindmapMarkdown" />
      </section>
      <section class="rdr-block">
        <h3>节点层级</h3>
        <ul>
          <li v-for="node in payload.nodes" :key="node.id">
            {{ node.parentId ? `↳ ${node.label}` : node.label }}
          </li>
        </ul>
      </section>
      <section v-if="mindmapRelations.length" class="rdr-block">
        <h3>关系</h3>
        <ul><li v-for="relation in mindmapRelations" :key="`${relation.from}-${relation.to}`">{{ relation.from }} —{{ relation.label }}→ {{ relation.to }}</li></ul>
      </section>
      <section v-if="payload.pitfalls.length" class="rdr-block">
        <h3>易错点</h3>
        <ul><li v-for="item in payload.pitfalls" :key="item">{{ item }}</li></ul>
      </section>
      </template>
    </template>

    <!-- quiz -->
    <template v-else-if="quizPayload">
      <p class="rdr-muted">提交前不显示正确答案与解析；提交后将逐题返回答案、解析与错因标签。</p>
      <section v-for="question in quizPayload.questions" :key="question.questionId" class="rdr-block rdr-question">
        <h3>{{ question.question }}</h3>
        <p class="rdr-meta">{{ question.knowledgePoint }} · {{ question.difficulty }}</p>
        <div v-if="question.options.length" class="rdr-options">
          <label v-for="option in question.options" :key="option.key" :class="{ selected: quizAnswers[question.questionId] === option.key }">
            <input v-model="quizAnswers[question.questionId]" type="radio" :name="`q-${question.questionId}`" :value="option.key" :disabled="Boolean(quizResult)" />
            {{ option.key }}. {{ option.text }}
          </label>
        </div>
        <textarea v-else v-model="quizAnswers[question.questionId]" class="rdr-open-answer" rows="3" placeholder="输入你的答案" :disabled="Boolean(quizResult)"></textarea>
      </section>
      <p v-if="quizError" class="rdr-error">{{ quizError }}</p>
      <button v-if="!quizResult" class="rdr-primary" type="button" :disabled="quizSubmitting || !allChoiceAnswered" @click="submitQuiz">
        {{ quizSubmitting ? '提交中' : '提交练习' }}
      </button>
      <section v-if="quizResult" class="rdr-block rdr-result">
        <h3>批改结果{{ quizResult.score !== null ? `：客观题 ${quizResult.score} 分` : '' }}</h3>
        <p class="rdr-muted">{{ quizResult.message }}</p>
        <article v-for="item in quizResult.items" :key="item.questionId" class="rdr-sub-card">
          <strong>
            {{ item.correct === true ? '✓ 回答正确' : item.correct === false ? '✗ 回答错误' : '未作答 / 开放题，请对照参考答案' }}
          </strong>
          <p>正确答案：{{ item.correctAnswer }}</p>
          <p v-if="item.explanation">解析：{{ item.explanation }}</p>
          <p v-if="item.diagnosticLabel">错因标签：{{ item.diagnosticLabel }}</p>
          <p v-if="item.knowledgePoint">关联知识点：{{ item.knowledgePoint }}</p>
        </article>
      </section>
    </template>

    <!-- codelab -->
    <template v-else-if="payload?.kind === 'codelab'">
      <section class="rdr-block">
        <h3>实验目标</h3>
        <p>{{ payload.goal }}</p>
        <p v-if="payload.prerequisites.length" class="rdr-muted">前置知识：{{ payload.prerequisites.join('、') }}</p>
      </section>
      <section class="rdr-block">
        <div class="rdr-code-head">
          <h3>示例代码（{{ payload.language }}）</h3>
          <button type="button" class="rdr-secondary" @click="copyStarterCode">{{ copied ? '已复制' : '复制代码' }}</button>
        </div>
        <RichContentRenderer :content="codelabCodeMarkdown" />
      </section>
      <section v-if="payload.steps.length" class="rdr-block">
        <h3>实验步骤</h3>
        <ol><li v-for="(step, index) in payload.steps" :key="index">{{ step }}</li></ol>
      </section>
      <section class="rdr-block">
        <h3>输入与参考预期输出</h3>
        <p>输入：{{ payload.sampleInput || '运行示例代码即可。' }}</p>
        <pre class="rdr-output">{{ payload.expectedOutput || '（未提供参考输出）' }}</pre>
        <p class="rdr-muted">{{ payload.executionNote }}</p>
      </section>
      <section v-if="payload.explanation" class="rdr-block">
        <h3>说明</h3>
        <p>{{ payload.explanation }}</p>
      </section>
    </template>

    <!-- animation_script -->
    <template v-else-if="payload?.kind === 'animation_script'">
      <section class="rdr-block">
        <h3>学习目标</h3>
        <p>{{ payload.learningGoal || '通过分镜演示理解知识点的执行过程。' }}</p>
        <p class="rdr-media-note">{{ payload.mediaNote }}</p>
      </section>
      <section class="rdr-block">
        <h3>分镜脚本</h3>
        <article v-for="scene in payload.scenes" :key="scene.sceneNo" class="rdr-sub-card">
          <strong>{{ scene.sceneNo }}<template v-if="scene.durationSeconds !== null"> · {{ scene.durationSeconds }}s</template>{{ scene.title ? ' · ' + scene.title : '' }}</strong>
          <p>旁白：{{ scene.narration }}</p>
          <p v-if="scene.visualAction">画面动作：{{ scene.visualAction }}</p>
        </article>
      </section>
      <section v-if="payload.states.length" class="rdr-block">
        <h3>状态顺序</h3>
        <ol><li v-for="state in payload.states" :key="state">{{ state }}</li></ol>
      </section>
    </template>
  </div>
</template>

<style scoped>
.resource-detail-renderer { display: grid; gap: 14px; color: var(--color-ink); }
.rdr-block h3 { margin: 0 0 8px; font-size: 17px; }
.rdr-block p, .rdr-block li { color: rgba(31, 54, 49, 0.78); line-height: 1.75; margin: 4px 0; overflow-wrap: anywhere; }
.rdr-block ul, .rdr-block ol { margin: 4px 0; padding-left: 22px; }
.rdr-muted { color: rgba(31, 54, 49, 0.6) !important; font-size: 13px; }
.rdr-sub-card { border-left: 3px solid var(--color-primary); background: rgba(0, 121, 102, 0.05); border-radius: 10px; margin: 8px 0; padding: 10px 14px; }
.rdr-sub-card p { margin: 4px 0; }
.rdr-fallback summary { cursor: pointer; color: rgba(31, 54, 49, 0.62); font-size: 13px; }
.rdr-meta { font-size: 12px; color: rgba(31, 54, 49, 0.55) !important; }
.rdr-options { display: grid; gap: 8px; }
.rdr-options label { border: 1px solid rgba(31, 54, 49, 0.12); border-radius: 10px; cursor: pointer; display: flex; align-items: center; gap: 10px; padding: 9px 12px; }
.rdr-options label.selected { border-color: rgba(0, 121, 102, 0.4); background: rgba(0, 121, 102, 0.07); }
.rdr-open-answer { border: 1px solid rgba(31, 54, 49, 0.14); border-radius: 10px; font: inherit; padding: 10px 12px; width: 100%; box-sizing: border-box; }
.rdr-primary { border: 0; border-radius: 10px; background: var(--color-primary); color: #fff; cursor: pointer; font-weight: 800; min-height: 42px; padding: 0 22px; justify-self: start; }
.rdr-primary:disabled { cursor: not-allowed; opacity: 0.6; }
.rdr-secondary { border: 1px solid rgba(0, 121, 102, 0.22); border-radius: 999px; background: rgba(0, 121, 102, 0.08); color: var(--color-primary); cursor: pointer; font-weight: 800; padding: 6px 14px; }
.rdr-code-head { align-items: center; display: flex; gap: 12px; justify-content: space-between; }
.rdr-code-head h3 { margin: 0; }
.rdr-output { background: rgba(247, 250, 248, 0.95); border: 1px dashed rgba(31, 54, 49, 0.2); border-radius: 10px; overflow-x: auto; padding: 12px 14px; white-space: pre-wrap; }
.rdr-error { color: #df5b45; font-weight: 700; }
.rdr-media-note { border: 1px solid rgba(247, 153, 57, 0.35); background: rgba(247, 153, 57, 0.1); border-radius: 10px; padding: 8px 12px; }
</style>
