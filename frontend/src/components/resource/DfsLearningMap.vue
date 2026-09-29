<script setup lang="ts">
const learningSteps = [
  { no: '01', title: '选择起点', detail: '从节点 A 开始，把它标记为已访问。', stack: 'A' },
  { no: '02', title: '沿边深入', detail: '优先选择一个未访问邻居，例如从 A 走到 B。', stack: 'A → B' },
  { no: '03', title: '继续探索', detail: 'B 还有未访问邻居，就继续访问 D。', stack: 'A → B → D' },
  { no: '04', title: '遇到尽头', detail: 'D 没有未访问邻居，结束 D 的探索。', stack: 'A → B' },
  { no: '05', title: '回溯换路', detail: '返回 B，再访问尚未探索的 E。', stack: 'A → B → E' },
  { no: '06', title: '完成遍历', detail: '重复“深入—回溯”，直到所有节点都被访问。', stack: '空栈' },
];

const applications = [
  { title: '路径搜索', detail: '寻找从起点能够到达的节点' },
  { title: '环检测', detail: '发现指向递归栈中节点的回边' },
  { title: '连通分量', detail: '找出彼此连通的节点集合' },
];
</script>

<template>
  <section class="dfs-learning-map" aria-label="DFS 学生思维导图">
    <header class="map-hero">
      <div class="map-hero__icon" aria-hidden="true">DFS</div>
      <div>
        <span>学生版知识地图</span>
        <h3>深度优先搜索到底在做什么？</h3>
        <p>像走迷宫一样：先沿一条路走到底，走不通时退回上一个路口，再换一条路。</p>
      </div>
    </header>

    <div class="map-principle">
      <article>
        <b>核心动作</b>
        <strong>深入</strong>
        <p>只要还有没访问过的邻居，就继续向前走。</p>
      </article>
      <i aria-hidden="true">→</i>
      <article>
        <b>判断条件</b>
        <strong>还有新邻居吗？</strong>
        <p>有就继续深入，没有就准备返回。</p>
      </article>
      <i aria-hidden="true">→</i>
      <article>
        <b>关键动作</b>
        <strong>回溯</strong>
        <p>回到上一个节点，继续检查其他分支。</p>
      </article>
    </div>

    <section class="map-section">
      <div class="map-section__heading">
        <span>HOW IT WORKS</span>
        <h4>一次 DFS 中，图与递归栈如何同步变化</h4>
      </div>
      <div class="map-steps">
        <article v-for="step in learningSteps" :key="step.no" class="map-step">
          <span>{{ step.no }}</span>
          <div>
            <strong>{{ step.title }}</strong>
            <p>{{ step.detail }}</p>
          </div>
          <em>递归栈：{{ step.stack }}</em>
        </article>
      </div>
    </section>

    <section class="map-result">
      <div>
        <span>最终访问顺序</span>
        <strong>A → B → D → E → C → F → G</strong>
      </div>
      <p>节点只在第一次到达时被访问；出栈表示该节点的分支已经探索完成。</p>
    </section>

    <section class="map-section">
      <div class="map-section__heading">
        <span>WHERE TO USE</span>
        <h4>学会后可以解决什么问题？</h4>
      </div>
      <div class="map-applications">
        <article v-for="item in applications" :key="item.title">
          <strong>{{ item.title }}</strong>
          <p>{{ item.detail }}</p>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.dfs-learning-map { display:grid; gap:20px; border:1px solid rgba(49,126,83,.18); border-radius:22px; padding:24px; background:linear-gradient(145deg,#f7fbf5,#fff); color:#173c30; }
.map-hero { display:flex; align-items:center; gap:18px; }
.map-hero__icon { display:grid; width:70px; height:70px; flex:0 0 70px; place-items:center; border-radius:22px; background:linear-gradient(145deg,#3f8d57,#286f43); color:#fff; font-weight:900; box-shadow:0 12px 24px rgba(48,125,79,.2); }
.map-hero span,.map-section__heading span,.map-result span { color:#367c51; font-size:11px; font-weight:900; letter-spacing:.1em; }
.map-hero h3 { margin:4px 0 5px; font-size:24px; }
.map-hero p,.map-principle p,.map-step p,.map-result p,.map-applications p { margin:0; color:rgba(23,60,48,.66); line-height:1.6; }
.map-principle { display:grid; grid-template-columns:1fr auto 1fr auto 1fr; align-items:center; gap:12px; }
.map-principle article { display:grid; gap:5px; min-height:122px; align-content:center; border-radius:16px; padding:17px; background:#fff; box-shadow:0 10px 24px rgba(43,91,61,.07); }
.map-principle b { color:rgba(23,60,48,.48); font-size:11px; }
.map-principle strong { color:#286f43; font-size:18px; }
.map-principle > i { color:#4b9564; font-size:24px; font-style:normal; }
.map-section { display:grid; gap:14px; }
.map-section__heading h4 { margin:4px 0 0; font-size:18px; }
.map-steps { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.map-step { display:grid; grid-template-columns:38px minmax(0,1fr); gap:6px 12px; align-items:start; border:1px solid rgba(49,126,83,.12); border-radius:15px; padding:14px; background:rgba(255,255,255,.82); }
.map-step > span { display:grid; width:34px; height:34px; grid-row:1/3; place-items:center; border-radius:50%; background:#e4f2df; color:#2c7547; font-size:11px; font-weight:900; }
.map-step div { display:grid; gap:3px; }
.map-step strong { font-size:14px; }
.map-step p { font-size:12px; }
.map-step em { grid-column:2; width:max-content; max-width:100%; border-radius:999px; padding:5px 9px; background:#f0f6ed; color:#39794e; font-size:10px; font-style:normal; font-weight:800; }
.map-result { display:flex; align-items:center; justify-content:space-between; gap:20px; border-radius:16px; padding:18px 20px; background:linear-gradient(120deg,#e5f2df,#f8fbf5); }
.map-result div { display:grid; gap:5px; }
.map-result strong { color:#276e42; font-size:20px; letter-spacing:.03em; }
.map-result p { max-width:430px; font-size:12px; }
.map-applications { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }
.map-applications article { border-left:4px solid #4b9564; border-radius:10px; padding:13px 15px; background:#fff; }
.map-applications strong { color:#286f43; }
.map-applications p { margin-top:4px; font-size:12px; }
@media (max-width:760px) { .map-principle,.map-steps,.map-applications { grid-template-columns:1fr; }.map-principle > i { transform:rotate(90deg); justify-self:center; }.map-result { align-items:flex-start; flex-direction:column; }.map-hero { align-items:flex-start; }.map-hero__icon { width:54px; height:54px; flex-basis:54px; border-radius:16px; } }
</style>
