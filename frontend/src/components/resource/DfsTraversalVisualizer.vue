<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

type StepKind = 'visit' | 'return' | 'finish';

interface DfsStep {
  node: string;
  stack: string[];
  kind: StepKind;
  description: string;
}

const traversalOrder = ['A', 'B', 'D', 'E', 'C', 'F', 'G'];
const steps: DfsStep[] = [
  { node: 'A', stack: ['A'], kind: 'visit', description: '访问 A，压入递归栈' },
  { node: 'B', stack: ['A', 'B'], kind: 'visit', description: '从 A 访问 B，B 入栈' },
  { node: 'D', stack: ['A', 'B', 'D'], kind: 'visit', description: '从 B 访问 D，D 入栈' },
  { node: 'D', stack: ['A', 'B'], kind: 'return', description: 'D 无未访问邻居，D 出栈' },
  { node: 'E', stack: ['A', 'B', 'E'], kind: 'visit', description: '回到 B 后访问 E，E 入栈' },
  { node: 'E', stack: ['A', 'B'], kind: 'return', description: 'E 无未访问邻居，E 出栈' },
  { node: 'B', stack: ['A'], kind: 'return', description: 'B 的相邻节点处理完毕，B 出栈' },
  { node: 'C', stack: ['A', 'C'], kind: 'visit', description: '回到 A 后访问 C，C 入栈' },
  { node: 'F', stack: ['A', 'C', 'F'], kind: 'visit', description: '从 C 访问 F，F 入栈' },
  { node: 'F', stack: ['A', 'C'], kind: 'return', description: 'F 无未访问邻居，F 出栈' },
  { node: 'G', stack: ['A', 'C', 'G'], kind: 'visit', description: '从 C 访问 G，G 入栈' },
  { node: 'G', stack: ['A', 'C'], kind: 'return', description: 'G 无未访问邻居，G 出栈' },
  { node: 'A', stack: [], kind: 'finish', description: 'C 回溯，A 探索完成，递归栈清空' },
];

const graphRoot = ref<HTMLElement | null>(null);
const activeIndex = ref(0);
const isPlaying = ref(false);
let timer: number | undefined;

const activeStep = computed(() => steps[activeIndex.value]);
const visitedNodes = computed(() => {
  const visited = new Set<string>();
  steps.slice(0, activeIndex.value + 1).forEach((step) => {
    if (step.kind === 'visit') visited.add(step.node);
  });
  return visited;
});
const returnedNodes = computed(() => {
  const returned = new Set<string>();
  steps.slice(0, activeIndex.value + 1).forEach((step) => {
    if (step.kind !== 'visit') returned.add(step.node);
  });
  return returned;
});

const renderGraph = async () => {
  const { default: mermaid } = await import('mermaid');
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    flowchart: { curve: 'linear', htmlLabels: false, nodeSpacing: 76, rankSpacing: 70 },
    themeVariables: {
      primaryColor: '#ffffff',
      primaryTextColor: '#173c34',
      primaryBorderColor: '#4c9565',
      lineColor: '#4c9565',
      fontFamily: 'Inter, PingFang SC, Microsoft YaHei, sans-serif',
    },
  });
  const source = `flowchart TB
    A((A)) --- B((B))
    A --- C((C))
    B --- D((D))
    B --- E((E))
    C --- F((F))
    C --- G((G))
    classDef treeNode fill:#fff,stroke:#4c9565,stroke-width:2px,color:#173c34,font-size:20px,font-weight:700
    class A,B,C,D,E,F,G treeNode
    linkStyle default stroke:#4c9565,stroke-width:2px`;
  const { svg } = await mermaid.render(`dfs-tree-${Date.now()}`, source);
  if (!graphRoot.value) return;
  // The Mermaid source above is fully static and owned by this component. Keeping the
  // generated foreignObject labels intact is necessary for visible A-G node text.
  graphRoot.value.innerHTML = svg;
  const svgRoot = graphRoot.value.querySelector<SVGSVGElement>('svg');
  const viewBox = svgRoot?.getAttribute('viewBox')?.split(/\s+/).map(Number);
  if (svgRoot && viewBox?.length === 4 && viewBox.every(Number.isFinite)) {
    const [x, y, width, height] = viewBox;
    svgRoot.setAttribute('viewBox', `${x - 24} ${y - 24} ${width + 48} ${height + 48}`);
  }
  graphRoot.value.querySelectorAll<SVGGElement>('.node').forEach((node) => {
    const nodeLabel = node.textContent?.trim() || node.id.match(/flowchart-([A-G])-/)?.[1] || '';
    const labelGroup = node.querySelector<SVGGElement>('.label');
    const circle = node.querySelector<SVGCircleElement>('circle');

    // Mermaid measures single-letter labels as 12 px foreignObjects in some Chromium
    // builds, which clips A-G. Preserve Mermaid's graph/layout and normalize only its
    // generated label layer to native SVG text for reliable cross-browser rendering.
    if (labelGroup) {
      const labelText = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      labelText.setAttribute('fill', '#173c34');
      labelText.setAttribute('font-size', '20');
      labelText.setAttribute('font-weight', '700');
      labelText.setAttribute('text-anchor', 'middle');
      labelText.setAttribute('dominant-baseline', 'central');
      labelText.textContent = nodeLabel;
      labelGroup.setAttribute('transform', 'translate(0, 0)');
      labelGroup.replaceChildren(labelText);
    }
    circle?.setAttribute('r', '25');
  });
  await nextTick();
  updateGraphState();
};

const updateGraphState = () => {
  const nodes = Array.from(graphRoot.value?.querySelectorAll<SVGElement>('.node') ?? []);
  nodes.forEach((element) => {
    const label = element.textContent?.trim() || '';
    element.classList.toggle('is-visited', visitedNodes.value.has(label));
    element.classList.toggle('is-returned', returnedNodes.value.has(label));
    element.classList.toggle('is-current', label === activeStep.value.node);
  });
};

const goTo = (index: number) => {
  activeIndex.value = Math.min(steps.length - 1, Math.max(0, index));
};

const stopPlayback = () => {
  window.clearInterval(timer);
  timer = undefined;
  isPlaying.value = false;
};

const togglePlayback = () => {
  if (isPlaying.value) {
    stopPlayback();
    return;
  }
  if (activeIndex.value === steps.length - 1) activeIndex.value = 0;
  isPlaying.value = true;
  timer = window.setInterval(() => {
    if (activeIndex.value >= steps.length - 1) {
      stopPlayback();
      return;
    }
    activeIndex.value += 1;
  }, 1050);
};

watch(activeIndex, updateGraphState);
onMounted(renderGraph);
onUnmounted(stopPlayback);
</script>

<template>
  <section class="dfs-visualizer" aria-label="DFS 深度优先搜索交互演示">
    <header class="visualizer-head">
      <div>
        <span>VisualizationAgent · 个性化图解</span>
        <h3>DFS 深度优先搜索：图结构与递归栈同步演示</h3>
        <p>针对“递归调用栈”薄弱点，把访问、入栈和回溯过程拆成 13 个可操作步骤。</p>
      </div>
      <div class="step-progress">
        <strong>{{ activeIndex + 1 }}</strong>
        <span>/ {{ steps.length }} 步</span>
      </div>
    </header>

    <div class="visualizer-controls">
      <button type="button" :disabled="activeIndex === 0" @click="goTo(activeIndex - 1)">上一步</button>
      <button class="play-button" type="button" @click="togglePlayback">
        {{ isPlaying ? '暂停演示' : '自动演示' }}
      </button>
      <button type="button" :disabled="activeIndex === steps.length - 1" @click="goTo(activeIndex + 1)">下一步</button>
      <button type="button" @click="goTo(0)">重新开始</button>
      <div class="active-explanation">
        <span>当前动作</span>
        <strong>{{ activeStep.description }}</strong>
      </div>
    </div>

    <div class="visualizer-grid">
      <article class="graph-card">
        <div class="section-label"><b>1</b> 图结构示例</div>
        <div ref="graphRoot" class="dfs-graph" aria-label="A 到 G 的 DFS 示例图"></div>

        <div class="section-label order-label"><b>2</b> 从 A 开始的 DFS 遍历顺序</div>
        <div class="traversal-order" aria-label="DFS 遍历顺序 A B D E C F G">
          <template v-for="(node, index) in traversalOrder" :key="node">
            <span
              class="order-node"
              :class="{
                visited: visitedNodes.has(node),
                current: activeStep.node === node,
              }"
            >{{ node }}</span>
            <i v-if="index < traversalOrder.length - 1" aria-hidden="true">→</i>
          </template>
        </div>

        <div class="live-stack-card">
          <div>
            <span>当前递归栈</span>
            <small>栈底 → 栈顶</small>
          </div>
          <div v-if="activeStep.stack.length" class="live-stack">
            <span v-for="node in activeStep.stack" :key="node" :class="{ top: node === activeStep.stack.at(-1) }">{{ node }}</span>
          </div>
          <strong v-else class="empty-stack">空栈 · 遍历完成</strong>
        </div>
      </article>

      <article class="stack-table-card">
        <div class="section-label"><b>3</b> 递归栈变化过程</div>
        <div class="stack-table-head" aria-hidden="true">
          <span>步骤</span>
          <span>当前访问</span>
          <span>递归栈（栈底 → 栈顶）</span>
          <span>说明</span>
        </div>
        <button
          v-for="(step, index) in steps"
          :key="`${index}-${step.node}`"
          class="stack-row"
          :class="{ active: activeIndex === index, passed: activeIndex > index }"
          type="button"
          @click="goTo(index)"
        >
          <span class="step-number">{{ index + 1 }}</span>
          <strong>{{ step.kind === 'return' ? '—' : step.node }}</strong>
          <span class="stack-cells" :class="{ empty: !step.stack.length }">
            <template v-if="step.stack.length">
              <i v-for="node in step.stack" :key="node">{{ node }}</i>
            </template>
            <b v-else>空栈</b>
          </span>
          <span class="step-description">{{ step.description }}</span>
        </button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.dfs-visualizer {
  --dfs-green: #3f8d57;
  --dfs-green-dark: #246d40;
  --dfs-green-soft: #e9f4e5;
  border: 1px solid rgba(63, 141, 87, .2);
  border-radius: 22px;
  background: #fbfdf9;
  box-shadow: 0 18px 44px rgba(42, 96, 58, .1);
  color: #193b30;
  margin-bottom: 22px;
  max-width: 100%;
  overflow: hidden;
}

.visualizer-head {
  align-items: flex-start;
  background: linear-gradient(135deg, #f4f9ef, #fff);
  border-bottom: 1px solid rgba(63, 141, 87, .14);
  display: flex;
  gap: 24px;
  justify-content: space-between;
  padding: 22px 24px 20px;
}

.visualizer-head > div:first-child { display: grid; gap: 5px; }
.visualizer-head span { color: var(--dfs-green-dark); font-size: 12px; font-weight: 800; letter-spacing: .02em; }
.visualizer-head h3 { color: #153c2d; font-size: 22px; line-height: 1.35; margin: 0; }
.visualizer-head p { color: rgba(25, 59, 48, .66); font-size: 13px; line-height: 1.65; margin: 0; }

.step-progress { align-items: baseline; display: flex; flex: 0 0 auto; }
.step-progress strong { color: var(--dfs-green-dark); font-size: 30px; line-height: 1; }
.step-progress span { color: rgba(25, 59, 48, .5); }

.visualizer-controls {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 24px;
}

.visualizer-controls button {
  border: 1px solid rgba(63, 141, 87, .22);
  border-radius: 9px;
  background: #fff;
  color: var(--dfs-green-dark);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 800;
  padding: 8px 12px;
  transition: background .18s ease, transform .18s ease;
}

.visualizer-controls button:hover:not(:disabled) { background: var(--dfs-green-soft); transform: translateY(-1px); }
.visualizer-controls button:disabled { cursor: not-allowed; opacity: .38; }
.visualizer-controls .play-button { background: var(--dfs-green); border-color: var(--dfs-green); color: #fff; }
.active-explanation { display: grid; gap: 1px; margin-left: auto; min-width: 260px; text-align: right; }
.active-explanation span { color: rgba(25, 59, 48, .5); font-size: 10px; }
.active-explanation strong { color: #245e3d; font-size: 12px; }

.visualizer-grid {
  display: grid;
  gap: 18px;
  grid-template-columns: minmax(350px, .88fr) minmax(0, 1.12fr);
  padding: 0 24px 24px;
}

.graph-card,
.stack-table-card {
  border: 1px solid rgba(63, 141, 87, .14);
  border-radius: 17px;
  background: rgba(255, 255, 255, .86);
  min-width: 0;
  padding: 16px;
}

.section-label {
  align-items: center;
  border-radius: 8px;
  background: linear-gradient(100deg, #397f4d, #5a9d67);
  color: #fff;
  display: inline-flex;
  font-size: 14px;
  font-weight: 850;
  gap: 8px;
  line-height: 1;
  padding: 9px 13px;
}

.section-label b { font-size: 12px; }
.dfs-graph { align-items: center; display: flex; height: 270px; justify-content: center; overflow: hidden; }
.dfs-graph :deep(svg) { height: 250px !important; max-width: 100%; width: auto !important; }
.dfs-graph :deep(.node) { opacity: 1; transition: filter .25s ease; }
.dfs-graph :deep(.node:not(.is-visited) .label) { opacity: .42; }
.dfs-graph :deep(.node:not(.is-visited) .label-container) { fill: #fbfdf9 !important; stroke: #9fc8aa !important; }
.dfs-graph :deep(.node.is-returned .label-container) { fill: #eef6e9 !important; }
.dfs-graph :deep(.node.is-current) { filter: drop-shadow(0 7px 8px rgba(44, 117, 65, .32)); }
.dfs-graph :deep(.node.is-current .label-container) { fill: #dff0d8 !important; stroke: #287440 !important; stroke-width: 3px !important; }

.order-label { margin-top: 2px; }
.traversal-order { align-items: center; display: flex; gap: 6px; justify-content: center; padding: 18px 2px; }
.order-node { align-items: center; border: 1px solid #a7c6a0; border-radius: 50%; background: #f5f8f2; color: rgba(25, 59, 48, .38); display: inline-flex; flex: 0 0 34px; font-size: 15px; font-weight: 850; height: 34px; justify-content: center; transition: .22s ease; }
.order-node.visited { background: #e6f1df; color: #1f5636; }
.order-node.current { background: var(--dfs-green); border-color: var(--dfs-green); box-shadow: 0 0 0 5px rgba(63, 141, 87, .12); color: #fff; transform: scale(1.08); }
.traversal-order i { color: #43845a; font-size: 16px; font-style: normal; }

.live-stack-card { align-items: center; border-radius: 12px; background: #f3f8ef; display: flex; gap: 14px; justify-content: space-between; padding: 13px 14px; }
.live-stack-card > div:first-child { display: grid; }
.live-stack-card span { color: #245e3d; font-size: 12px; font-weight: 800; }
.live-stack-card small { color: rgba(25, 59, 48, .5); font-size: 10px; }
.live-stack { display: flex; gap: 5px; }
.live-stack span { border: 1px solid #9abd8f; border-radius: 5px; background: #fff; min-width: 30px; padding: 5px 8px; text-align: center; }
.live-stack span.top { background: #dbeed4; border-color: #4c9565; }
.empty-stack { color: var(--dfs-green-dark); font-size: 12px; }

.stack-table-card { overflow: hidden; }
.stack-table-head,
.stack-row { display: grid; gap: 8px; grid-template-columns: 42px 66px minmax(130px, .8fr) minmax(140px, 1.2fr); }
.stack-table-head { border-bottom: 1px solid rgba(63, 141, 87, .18); color: var(--dfs-green-dark); font-size: 11px; font-weight: 850; margin-top: 12px; padding: 8px 9px; }
.stack-row { align-items: center; border: 0; border-bottom: 1px solid rgba(63, 141, 87, .09); background: transparent; color: #294a3e; cursor: pointer; font: inherit; min-height: 44px; padding: 5px 9px; text-align: left; transition: background .18s ease, transform .18s ease; width: 100%; }
.stack-row:hover { background: rgba(233, 244, 229, .65); }
.stack-row.active { border-radius: 9px; background: #e8f3e3; box-shadow: inset 3px 0 #3f8d57; transform: translateX(2px); }
.stack-row.passed .step-number { background: #79aa75; }
.step-number { align-items: center; border-radius: 50%; background: #a9bea6; color: #fff; display: inline-flex; font-size: 10px; font-weight: 850; height: 25px; justify-content: center; width: 25px; }
.stack-row.active .step-number { background: var(--dfs-green); box-shadow: 0 0 0 4px rgba(63, 141, 87, .12); }
.stack-row > strong { color: #224f38; font-size: 13px; text-align: center; }
.stack-cells { align-items: center; display: flex; gap: 4px; }
.stack-cells i { border: 1px solid #a2c296; border-radius: 4px; background: #f3f8ef; color: #315b3d; font-size: 11px; font-style: normal; font-weight: 750; min-width: 28px; padding: 3px 6px; text-align: center; }
.stack-cells.empty b { color: #315b3d; font-size: 11px; }
.step-description { color: rgba(25, 59, 48, .72); font-size: 11px; line-height: 1.45; }

@media (max-width: 1180px) {
  .visualizer-grid { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .visualizer-head { flex-direction: column; padding: 18px; }
  .visualizer-head h3 { font-size: 18px; }
  .visualizer-controls { padding: 12px 18px; }
  .active-explanation { margin-left: 0; min-width: 100%; text-align: left; }
  .visualizer-grid { padding: 0 12px 16px; }
  .graph-card, .stack-table-card { padding: 12px; }
  .dfs-graph { height: 230px; }
  .dfs-graph :deep(svg) { height: 220px !important; }
  .traversal-order { gap: 3px; overflow-x: auto; justify-content: flex-start; }
  .order-node { flex-basis: 30px; height: 30px; }
  .stack-table-card { overflow-x: auto; }
  .stack-table-head, .stack-row { grid-template-columns: 40px 64px 150px 190px; min-width: 500px; }
}

@media (prefers-reduced-motion: reduce) {
  .dfs-visualizer * { transition: none !important; }
}
</style>
