<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import DOMPurify from 'dompurify';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import c from 'highlight.js/lib/languages/c';
import cpp from 'highlight.js/lib/languages/cpp';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import python from 'highlight.js/lib/languages/python';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import 'highlight.js/styles/github.css';

const props = defineProps<{ content: string; contentFormat?: string }>();
const rootElement = ref<HTMLElement | null>(null);
let mermaidModule: Promise<typeof import('mermaid')> | undefined;

hljs.registerLanguage('bash', bash);
hljs.registerLanguage('c', c);
hljs.registerLanguage('cpp', cpp);
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('js', javascript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('python', python);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('ts', typescript);
hljs.registerLanguage('html', xml);
hljs.registerLanguage('xml', xml);

const escapeHtml = (value: string) => value.replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[character] || character);
const looksLikeMermaid = (value: string) => /^(?:\s*%%[^\n]*\n\s*)*(?:flowchart|graph|mindmap|sequenceDiagram|stateDiagram(?:-v2)?|classDiagram|erDiagram|journey|timeline|pie|gantt)\b/m.test(value);
const safeLanguageName = (value: string) => value.toLowerCase().replace(/[^a-z0-9_-]/g, '');

const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, language): string {
    const normalizedLanguage = safeLanguageName(language || '');
    const isMermaid = normalizedLanguage === 'mermaid' || looksLikeMermaid(code);
    if (isMermaid) {
      return `<pre class="mermaid-source"><code class="language-mermaid">${escapeHtml(code)}</code></pre>`;
    }
    if (normalizedLanguage && hljs.getLanguage(normalizedLanguage)) {
      return `<pre class="hljs"><code class="language-${normalizedLanguage}">${hljs.highlight(code, { language: normalizedLanguage }).value}</code></pre>`;
    }
    const languageClass = normalizedLanguage ? ` class="language-${normalizedLanguage}"` : '';
    return `<pre class="hljs"><code${languageClass}>${escapeHtml(code)}</code></pre>`;
  },
});

const rendered = computed(() => {
  const source = props.content || '';
  if (props.contentFormat === 'json') {
    try {
      return `<pre class="hljs"><code>${escapeHtml(JSON.stringify(JSON.parse(source), null, 2))}</code></pre>`;
    } catch {
      return `<pre class="hljs"><code>${escapeHtml(source)}</code></pre>`;
    }
  }
  return DOMPurify.sanitize(md.render(source), { ADD_ATTR: ['target'] });
});

const renderMermaid = async () => {
  await nextTick();
  const blocks = Array.from(rootElement.value?.querySelectorAll<HTMLElement>('pre code.language-mermaid') ?? []);
  if (!blocks.length) return;
  const { default: mermaid } = await (mermaidModule ??= import('mermaid'));
  mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    securityLevel: 'strict',
    // 使用原生 SVG text，避免 DOMPurify 的 SVG 清理移除 Mermaid foreignObject
    // 后只剩连线、箭头而丢失节点文字。
    flowchart: { curve: 'basis', htmlLabels: false, padding: 18 },
    themeVariables: {
      primaryColor: '#e7f6f1',
      primaryTextColor: '#153d35',
      primaryBorderColor: '#168a77',
      lineColor: '#4f746b',
      secondaryColor: '#fff4df',
      tertiaryColor: '#f7fbf9',
      fontFamily: 'Inter, PingFang SC, Microsoft YaHei, sans-serif',
    },
  });
  for (const [index, block] of blocks.entries()) {
    const pre = block.parentElement;
    if (!pre || pre.dataset.rendered === 'true') continue;
    try {
      const { svg } = await mermaid.render(`edupath-mermaid-${Date.now()}-${index}`, block.textContent || '');
      const wrapper = createInteractiveDiagram(svg, index);
      pre.replaceWith(wrapper);
    } catch {
      pre.classList.add('mermaid-error');
    }
  }
};

const createInteractiveDiagram = (svg: string, index: number) => {
  const wrapper = document.createElement('section');
  wrapper.className = 'mermaid-diagram';
  wrapper.tabIndex = 0;
  wrapper.setAttribute('aria-label', `交互式可视化图解 ${index + 1}`);

  const header = document.createElement('div');
  header.className = 'diagram-header';
  const heading = document.createElement('div');
  heading.className = 'diagram-heading';
  heading.innerHTML = '<strong>VisualizationAgent · 交互图解</strong><span>点击节点聚焦 · 拖拽画布 · 按钮或 Ctrl + 滚轮缩放</span>';
  const toolbar = document.createElement('div');
  toolbar.className = 'diagram-toolbar';

  const viewport = document.createElement('div');
  viewport.className = 'diagram-viewport';
  const canvas = document.createElement('div');
  canvas.className = 'diagram-canvas';
  canvas.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true } });
  viewport.append(canvas);

  const nodes = Array.from(canvas.querySelectorAll<SVGElement>('.node'));
  nodes.forEach((node) => {
    node.tabIndex = 0;
    node.setAttribute('role', 'button');
    node.setAttribute('aria-label', `聚焦节点：${node.textContent?.trim() || '图解节点'}`);
    const toggleFocus = () => {
      const willFocus = !node.classList.contains('is-focused');
      nodes.forEach((item) => item.classList.remove('is-focused'));
      canvas.classList.toggle('has-focused-node', willFocus);
      if (willFocus) node.classList.add('is-focused');
    };
    node.addEventListener('click', (event) => {
      event.stopPropagation();
      toggleFocus();
    });
    node.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        toggleFocus();
      }
    });
  });

  let scale = 1;
  let offsetX = 0;
  let offsetY = 0;
  let dragging = false;
  let dragStartX = 0;
  let dragStartY = 0;
  const scaleLabel = document.createElement('span');
  scaleLabel.className = 'diagram-scale';

  const updateTransform = () => {
    canvas.style.transform = `translate3d(${offsetX}px, ${offsetY}px, 0) scale(${scale})`;
    scaleLabel.textContent = `${Math.round(scale * 100)}%`;
  };
  const zoom = (delta: number) => {
    scale = Math.min(2.4, Math.max(0.55, Number((scale + delta).toFixed(2))));
    updateTransform();
  };
  const reset = () => {
    scale = 1;
    offsetX = 0;
    offsetY = 0;
    updateTransform();
  };
  const button = (label: string, title: string, action: () => void) => {
    const element = document.createElement('button');
    element.type = 'button';
    element.textContent = label;
    element.title = title;
    element.setAttribute('aria-label', title);
    element.addEventListener('click', action);
    return element;
  };

  toolbar.append(
    button('−', '缩小图解', () => zoom(-0.15)),
    scaleLabel,
    button('+', '放大图解', () => zoom(0.15)),
    button('复位', '恢复默认视图', reset),
    button('全屏', '切换全屏查看', () => {
      wrapper.classList.toggle('is-fullscreen');
      reset();
    }),
    button('下载图', '下载 SVG 图片', () => downloadDiagram(canvas.innerHTML, index)),
  );

  viewport.addEventListener('wheel', (event) => {
    if (!event.ctrlKey && !event.metaKey) return;
    event.preventDefault();
    zoom(event.deltaY < 0 ? 0.12 : -0.12);
  }, { passive: false });
  viewport.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    dragging = true;
    dragStartX = event.clientX - offsetX;
    dragStartY = event.clientY - offsetY;
    viewport.classList.add('is-dragging');
    viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener('pointermove', (event) => {
    if (!dragging) return;
    offsetX = event.clientX - dragStartX;
    offsetY = event.clientY - dragStartY;
    updateTransform();
  });
  const stopDragging = (event: PointerEvent) => {
    dragging = false;
    viewport.classList.remove('is-dragging');
    if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
  };
  viewport.addEventListener('pointerup', stopDragging);
  viewport.addEventListener('pointercancel', stopDragging);
  wrapper.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && wrapper.classList.contains('is-fullscreen')) {
      wrapper.classList.remove('is-fullscreen');
      reset();
    }
  });

  header.append(heading, toolbar);
  wrapper.append(header, viewport);
  updateTransform();
  return wrapper;
};

const downloadDiagram = (svg: string, index: number) => {
  const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `EduPath-可视化图解-${index + 1}.svg`;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

onMounted(renderMermaid);
watch(rendered, renderMermaid);
</script>

<template><article ref="rootElement" class="rich-content" v-html="rendered"></article></template>

<style scoped>
.rich-content { color: var(--color-ink); font-size: 15px; line-height: 1.85; overflow-wrap: anywhere; }
.rich-content :deep(h1), .rich-content :deep(h2), .rich-content :deep(h3) { color: rgba(18, 49, 43, .96); line-height: 1.35; margin: 1.4em 0 .6em; }
.rich-content :deep(h1:first-child), .rich-content :deep(h2:first-child), .rich-content :deep(h3:first-child) { margin-top: 0; }
.rich-content :deep(ul), .rich-content :deep(ol) { padding-left: 1.5em; }
.rich-content :deep(li) { margin: .3em 0; }
.rich-content :deep(pre) { border-radius: 14px; overflow-x: auto; padding: 18px; }
.rich-content :deep(table) { border-collapse: collapse; display: block; max-width: 100%; overflow-x: auto; }
.rich-content :deep(th), .rich-content :deep(td) { border: 1px solid rgba(31,54,49,.14); padding: 9px 12px; }
.rich-content :deep(blockquote) { border-left: 4px solid var(--color-primary); background: rgba(0,121,102,.06); margin-left: 0; padding: 10px 18px; }
.rich-content :deep(a) { color: var(--color-primary); }
.rich-content :deep(.mermaid-diagram) { border: 1px solid rgba(0,121,102,.16); border-radius: 20px; background: linear-gradient(145deg, rgba(239,249,246,.98), rgba(255,255,255,.98)); box-shadow: 0 16px 40px rgba(21,75,64,.1); margin: 20px 0; overflow: hidden; }
.rich-content :deep(.diagram-header) { align-items: center; background: rgba(255,255,255,.76); border-bottom: 1px solid rgba(0,121,102,.11); display: flex; gap: 18px; justify-content: space-between; padding: 13px 16px; }
.rich-content :deep(.diagram-heading) { display: grid; gap: 1px; line-height: 1.35; }
.rich-content :deep(.diagram-heading strong) { color: #087665; font-size: 14px; }
.rich-content :deep(.diagram-heading span) { color: rgba(31,54,49,.6); font-size: 11px; }
.rich-content :deep(.diagram-toolbar) { align-items: center; display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.rich-content :deep(.diagram-toolbar button) { border: 1px solid rgba(0,121,102,.16); border-radius: 9px; background: #fff; color: #125f53; cursor: pointer; font: inherit; font-size: 12px; font-weight: 750; line-height: 1; min-height: 30px; padding: 7px 10px; transition: transform .18s ease, background .18s ease, box-shadow .18s ease; }
.rich-content :deep(.diagram-toolbar button:hover) { background: #e7f6f1; box-shadow: 0 5px 14px rgba(0,121,102,.12); transform: translateY(-1px); }
.rich-content :deep(.diagram-scale) { color: rgba(31,54,49,.72); font-size: 11px; font-weight: 800; min-width: 38px; text-align: center; }
.rich-content :deep(.diagram-viewport) { align-items: center; cursor: grab; display: flex; justify-content: center; min-height: 430px; overflow: hidden; padding: 28px; touch-action: none; user-select: none; }
.rich-content :deep(.diagram-viewport.is-dragging) { cursor: grabbing; }
.rich-content :deep(.diagram-canvas) { transform-origin: center; transition: transform .18s ease-out; will-change: transform; }
.rich-content :deep(.diagram-viewport.is-dragging .diagram-canvas) { transition: none; }
.rich-content :deep(.mermaid-diagram svg) { display: block; height: auto; margin: 0 auto; max-height: 620px; max-width: none; min-width: 720px; }
.rich-content :deep(.mermaid-diagram svg .node) { animation: diagram-node-in .48s ease both; cursor: pointer; outline: none; transition: filter .2s ease, opacity .2s ease, transform .2s ease; transform-box: fill-box; transform-origin: center; }
.rich-content :deep(.diagram-canvas.has-focused-node .node:not(.is-focused)) { opacity: .24; }
.rich-content :deep(.mermaid-diagram svg .node.is-focused) { filter: drop-shadow(0 8px 10px rgba(0,121,102,.32)); transform: scale(1.06); }
.rich-content :deep(.mermaid-diagram svg .node:focus-visible) { filter: drop-shadow(0 0 6px rgba(0,121,102,.7)); }
.rich-content :deep(.mermaid-diagram svg .edgePath) { animation: diagram-edge-in .65s ease both; }
.rich-content :deep(.mermaid-diagram.is-fullscreen) { border-radius: 0; inset: 0; margin: 0; position: fixed; z-index: 9999; }
.rich-content :deep(.mermaid-diagram.is-fullscreen .diagram-viewport) { height: calc(100vh - 64px); }
.rich-content :deep(.mermaid-diagram.is-fullscreen svg) { max-height: calc(100vh - 120px); }
.rich-content :deep(.mermaid-error) { border: 1px solid rgba(201,72,60,.28); background: rgba(201,72,60,.05); }
.rich-content :deep(.mermaid-error)::before { color: #a83e34; content: '图示语法暂时无法渲染，以下保留原始内容便于检查：'; display: block; font-family: Inter, sans-serif; font-size: 12px; font-weight: 700; margin-bottom: 8px; }

@keyframes diagram-node-in { from { opacity: 0; transform: translateY(8px) scale(.96); } to { opacity: 1; transform: translateY(0) scale(1); } }
@keyframes diagram-edge-in { from { opacity: 0; } to { opacity: 1; } }

@media (max-width: 760px) {
  .rich-content :deep(.diagram-header) { align-items: flex-start; flex-direction: column; }
  .rich-content :deep(.diagram-toolbar) { justify-content: flex-start; }
  .rich-content :deep(.diagram-viewport) { min-height: 340px; padding: 16px; }
  .rich-content :deep(.mermaid-diagram svg) { min-width: 620px; }
}
</style>
