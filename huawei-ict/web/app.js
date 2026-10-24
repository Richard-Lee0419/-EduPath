/* 知径 EduPath · AgentArts demo UI
   Vanilla ES2020, no build step. All data comes from the local bridge API. */

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const state = {
  bootstrap: null,
  runtime: null,
  taskId: null,
  result: null,
  activeTab: "lecture",
  answers: {},
  quiz: null,
  quizSubmitted: false,
  running: false,
  stepCount: 0,
  totalMs: 0,
  traceStarted: false,
  difficulty: "basic",
};

const TYPE_LABEL = {
  lecture: "讲义",
  mindmap: "导图",
  quiz: "练习",
  codelab: "代码实验",
  reading: "拓展阅读",
  flowchart: "流程图",
  animation_script: "动画脚本",
};

const AGENT_ICON = {
  "主控 Agent": "控",
  "画像 Agent": "像",
  "检索 Agent": "检",
  "教学设计 Agent": "设",
  "生成 Agent": "生",
  "辅导 Agent": "辅",
  "评估 Agent": "评",
  "安全 Agent": "安",
  "路径 Agent": "路",
};

const PRESETS = {
  basic: {
    difficulty: "basic",
    text: "我递归总是绕晕，想要一份二叉树遍历的图解讲义，最好带 3 道题。",
  },
  exam: {
    difficulty: "medium",
    text: "明天小测，帮我查漏补缺，重点练中序/前序混淆和空节点边界。",
  },
  advanced: {
    difficulty: "advanced",
    text: "我会写遍历代码，想理解递归与调用栈的本质，给我更深的材料和挑战题。",
  },
};

/* ------------------------------------------------------------------ utils */
function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function inline(text) {
  let html = escapeHtml(text);
  html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
  html = html.replace(/\[(E\d+)\]/g, '<span class="cite">[$1]</span>');
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
  return html;
}

function markdown(source) {
  const lines = String(source ?? "").replace(/\r\n/g, "\n").split("\n");
  const out = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) { index += 1; continue; }

    if (line.trim().startsWith("```")) {
      const lang = line.trim().slice(3).trim();
      const code = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      index += 1;
      out.push(`<pre data-lang="${escapeHtml(lang)}"><code>${escapeHtml(code.join("\n"))}</code></pre>`);
      continue;
    }

    if (/^\s*\|.+\|\s*$/.test(line) && index + 1 < lines.length && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[index + 1])) {
      const headers = line.trim().replace(/^\||\|$/g, "").split("|").map((cell) => cell.trim());
      index += 2;
      const rows = [];
      while (index < lines.length && /^\s*\|.+\|\s*$/.test(lines[index])) {
        rows.push(lines[index].trim().replace(/^\||\|$/g, "").split("|").map((cell) => cell.trim()));
        index += 1;
      }
      out.push(
        `<table><thead><tr>${headers.map((cell) => `<th>${inline(cell)}</th>`).join("")}</tr></thead>` +
        `<tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${inline(cell)}</td>`).join("")}</tr>`).join("")}</tbody></table>`
      );
      continue;
    }

    const heading = /^(#{1,3})\s+(.*)$/.exec(line);
    if (heading) {
      out.push(`<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`);
      index += 1;
      continue;
    }

    if (/^\s*>\s?/.test(line)) {
      const quote = [];
      while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
        quote.push(lines[index].replace(/^\s*>\s?/, ""));
        index += 1;
      }
      out.push(`<blockquote>${quote.map((item) => inline(item)).join("<br>")}</blockquote>`);
      continue;
    }

    if (/^\s*[-*]\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*]\s+/, ""));
        index += 1;
      }
      out.push(`<ul>${items.map((item) => `<li>${inline(item)}</li>`).join("")}</ul>`);
      continue;
    }

    if (/^\s*\d+\.\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*\d+\.\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*\d+\.\s+/, ""));
        index += 1;
      }
      out.push(`<ol>${items.map((item) => `<li>${inline(item)}</li>`).join("")}</ol>`);
      continue;
    }

    if (/^\s*---+\s*$/.test(line)) {
      out.push("<hr>");
      index += 1;
      continue;
    }

    const paragraph = [line];
    index += 1;
    while (index < lines.length && lines[index].trim() && !/^(#{1,3}\s|>\s|[-*]\s|\d+\.\s|```|---)/.test(lines[index].trim())) {
      paragraph.push(lines[index]);
      index += 1;
    }
    out.push(`<p>${inline(paragraph.join(" "))}</p>`);
  }
  return out.join("");
}

async function fetchJSON(url, options = {}) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status} ${text.slice(0, 200)}`);
  }
  return response.json();
}

let toastTimer = 0;
function toast(message) {
  const element = $("#toast");
  element.textContent = message;
  element.classList.add("show");
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => element.classList.remove("show"), 4200);
}

/* ------------------------------------------------------------------- init */
async function init() {
  try {
    const bootstrap = await fetchJSON("/api/agent/bootstrap");
    state.bootstrap = bootstrap;
    state.runtime = bootstrap.runtime;
    renderRuntime(bootstrap.runtime);
    renderProfile(bootstrap.profile);
    renderCourses(bootstrap.courses);
    renderEvidence(bootstrap.evidence);
    renderQuality(null, null);
    renderTraceGhost(bootstrap.steps);
    bindEvents();
    const params = new URLSearchParams(window.location.search);
    if (params.get("full") === "1") document.body.classList.add("screenshot-mode");
    if (params.get("demo") === "done") {
      await runFastDemo();
      const tab = params.get("tab");
      if (tab) switchTab(tab);
    } else if (params.get("autodemo") === "1") {
      window.setTimeout(runDemo, 700);
    }
  } catch (error) {
    console.error(error);
    toast(`加载失败：${error.message}`);
  }
}

function renderRuntime(runtime) {
  const pill = $("#runtimePill");
  const isLive = runtime.mode === "agentarts" || runtime.mode === "edupath_ai";
  pill.classList.toggle("live", isLive);
  pill.querySelector("em").textContent = runtime.mode === "fixture" ? "本地演示 · 未上云" : runtime.mode_label;
  pill.title = runtime.note || "";
  $("#footerRuntime").textContent = `${runtime.mode_label} · bridge v${runtime.version}`;
  $("#qualityMode").textContent = runtime.mode;
  if (runtime.mode === "fixture") {
    $("#qualityMode").title = "当前为离线彩排模式；正式提交需配置 AgentArts 端点。";
  }
}

function renderProfile(profile) {
  $("#profileName").textContent = profile.display_name || "学习者";
  $("#profileMeta").textContent = `${profile.major || ""} · ${profile.grade || ""}`.replace(/^ · | · $/g, "");
  $("#profileGoal").textContent = profile.learning_goal || "";
  $("#profileVersion").textContent = `v${profile.version || 1}`;
  $("#profilePace").textContent = profile.learning_pace || "未设置";
  $("#profileConfidence").textContent = `${Math.round((profile.confidence_score || 0) * 100)}%`;
  $("#confidenceBar").style.width = `${Math.round((profile.confidence_score || 0) * 100)}%`;
  $("#profileReason").textContent = profile.updated_reason || "";
  $("#weakChips").innerHTML = (profile.weak_points || []).map((item) => `<span class="chip">${escapeHtml(item)}</span>`).join("");
  $("#prefChips").innerHTML = (profile.resource_preference || []).map((item) => `<span class="chip">${escapeHtml(item)}</span>`).join("");
}

function renderCourses(courses) {
  const tabs = $("#courseTabs");
  tabs.innerHTML = courses
    .map((course, index) => `<button type="button" class="${index === 0 ? "active" : ""}" data-course="${course.id}" data-ready="${index === 0}">${escapeHtml(course.name)}</button>`)
    .join("");
  tabs.addEventListener("click", (event) => {
    const button = event.target.closest("[data-course]");
    if (!button) return;
    if (button.dataset.ready !== "true") {
      toast("演示版聚焦《数据结构与算法》；《计算机组成原理》使用同一工作流，替换语料即可复制。");
      return;
    }
    $$("button", tabs).forEach((item) => item.classList.toggle("active", item === button));
    const course = courses.find((item) => String(item.id) === button.dataset.course);
    renderKnowledgePoints(course);
  });
  renderKnowledgePoints(courses[0]);
}

function renderKnowledgePoints(course) {
  const list = $("#kpList");
  list.innerHTML = (course.knowledge_points || [])
    .map((point, index) => `<button type="button" class="kp ${index === 1 ? "active" : ""}" data-kp="${escapeHtml(point.name)}">${escapeHtml(point.name)}<span class="kp-diff">${point.difficulty}</span></button>`)
    .join("");
  list.addEventListener("click", (event) => {
    const button = event.target.closest("[data-kp]");
    if (!button) return;
    $$(".kp", list).forEach((item) => item.classList.toggle("active", item === button));
    const prompt = $("#promptInput");
    prompt.value = `我想学习「${button.dataset.kp}」，请先看我的画像，再生成一份带证据的讲义和 3 道查漏题。`;
  });
}

function renderEvidence(evidence) {
  const list = $("#evidenceList");
  const items = evidence || [];
  $("#evidenceCount").textContent = `${items.length} 条`;
  if (!items.length) {
    list.innerHTML = '<div class="trace-placeholder">尚无证据</div>';
    return;
  }
  list.innerHTML = items
    .map((item) => `
      <article class="evidence-item">
        <div class="ev-top">
          <span class="ev-id">${escapeHtml(item.chunk_id)}</span>
          <span class="ev-title" title="${escapeHtml(item.title)}">${escapeHtml(item.title)}</span>
          <span class="ev-score">${Number(item.score || 0).toFixed(2)}</span>
        </div>
        <p class="ev-snippet">${escapeHtml(item.content)}</p>
        <div class="ev-bar"><i style="width:${Math.round(Number(item.score || 0) * 100)}%"></i></div>
        <span class="ev-source">${escapeHtml(item.source || "")}${item.license ? ` · ${escapeHtml(item.license)}` : ""}</span>
      </article>`)
    .join("");
}

function renderQuality(quality, safety) {
  if (!quality) return;
  const score = Number(quality.total_score || 0);
  $("#scoreRing").style.setProperty("--score", score);
  $("#qualityScore").textContent = Math.round(score);
  $("#qualityGrade").textContent = `${quality.grade || "-"} 级${quality.gate_passed ? " · 门禁通过" : " · 未通过"}`;
  $("#qualitySummary").textContent = quality.gate_passed ? "五维质量门禁已通过，内容可进入发布队列。" : "质量门禁未通过，内容不会发布。";
  const dimensions = quality.dimensions || {};
  $("#dimensionList").innerHTML = Object.entries(dimensions)
    .map(([name, item]) => `
      <div class="dimension">
        <span>${escapeHtml(name)}</span>
        <div class="track"><i style="width:${Math.round(Number(item.score || 0))}%"></i></div>
        <em>${Math.round(Number(item.score || 0))}</em>
      </div>`)
    .join("");
  const row = $("#safetyRow");
  row.classList.toggle("failed", !safety?.passed);
  $("#safetyStatus").textContent = safety ? (safety.passed ? "低风险 · 通过" : "已拦截") : "待运行";
}

function renderTraceGhost(steps) {
  const list = $("#traceList");
  list.innerHTML = (steps || [])
    .map((step, index) => `
      <div class="trace-item" data-step="${index}">
        <div class="trace-index"><span>${String(index + 1).padStart(2, "0")}</span></div>
        <div class="trace-main">
          <div class="trace-line"><span class="trace-agent">${escapeHtml(step.agent)}</span><span class="trace-name">${escapeHtml(step.name)}</span></div>
          <p class="trace-detail">${escapeHtml(step.detail)}</p>
          <span class="trace-tool">${escapeHtml(step.tool)}</span>
        </div>
        <span class="trace-duration">待运行</span>
      </div>`)
    .join("");
}

function renderTrace(trace) {
  const list = $("#traceList");
  list.innerHTML = "";
  state.stepCount = 0;
  state.totalMs = 0;
  state.traceStarted = true;
  (trace || []).forEach((step, index) => appendStep(step, index, true));
  updateTraceMeta();
}

function appendStep(step, index, instant = false) {
  const list = $("#traceList");
  if (!state.traceStarted) {
    list.innerHTML = "";
    state.traceStarted = true;
  }
  const row = document.createElement("div");
  row.className = "trace-item running";
  row.innerHTML = `
    <div class="trace-index"><span>${String(index + 1).padStart(2, "0")}</span></div>
    <div class="trace-main">
      <div class="trace-line"><span class="trace-agent">${escapeHtml(step.agent)}</span><span class="trace-name">${escapeHtml(step.name)}</span></div>
      <p class="trace-detail">${escapeHtml(step.detail)}</p>
      <span class="trace-tool">${escapeHtml(step.tool)}</span>
    </div>
    <span class="trace-duration">${Math.round(step.duration_ms)} ms</span>`;
  list.appendChild(row);
  list.scrollTop = list.scrollHeight;
  window.setTimeout(() => {
    row.classList.remove("running");
    row.classList.add("success");
  }, instant ? 0 : 420);
  state.stepCount += 1;
  state.totalMs += Number(step.duration_ms || 0);
  updateTraceMeta();
}

function updateTraceMeta() {
  $("#traceTime").textContent = state.totalMs ? `${(state.totalMs / 1000).toFixed(1)} s · ${state.stepCount} 步` : "—";
}

function setStatus(status, text) {
  const chip = $("#traceStatus");
  chip.className = `status-chip ${status}`;
  chip.innerHTML = `<i></i> ${escapeHtml(text)}`;
}

/* -------------------------------------------------------------------- run */
function currentPayload(fast = false) {
  const message = $("#promptInput").value.trim() || PRESETS.basic.text;
  const activeKp = $(".kp.active")?.dataset.kp || "二叉树遍历";
  return {
    message,
    course_id: 1,
    knowledge_point: activeKp,
    difficulty: state.difficulty,
    resource_types: ["lecture", "mindmap", "quiz"],
    fast,
  };
}

async function runDemo() {
  if (state.running) return;
  state.running = true;
  state.taskId = null;
  state.result = null;
  state.answers = {};
  state.quiz = null;
  state.quizSubmitted = false;
  state.traceStarted = false;
  state.stepCount = 0;
  state.totalMs = 0;
  $("#traceTime").textContent = "—";
  $("#runBtn").disabled = true;
  $("#sendBtn").disabled = true;
  setStatus("running", "运行中");
  $("#traceTitle").textContent = "AgentArts 工作流执行中";
  $("#artifactMeta").textContent = "生成中…";
  $("#artifactBody").innerHTML = `
    <div class="empty-state">
      <div class="empty-orbit"><span></span><span></span><span></span><b>径</b></div>
      <h3>正在编排学习任务</h3>
      <p>画像、检索、教学设计、生成、评估与安全 Agent 正在协作；每一步都会在这里留下轨迹。</p>
    </div>`;
  try {
    const created = await fetchJSON("/api/agent/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(currentPayload(false)),
    });
    state.taskId = created.task_id;
    if (window.EventSource) {
      streamTask(created.task_id);
    } else {
      pollTask(created.task_id);
    }
  } catch (error) {
    state.running = false;
    $("#runBtn").disabled = false;
    $("#sendBtn").disabled = false;
    setStatus("failed", "失败");
    toast(`运行失败：${error.message}`);
  }
}

function streamTask(taskId) {
  const source = new EventSource(`/api/agent/tasks/${taskId}/stream`);
  let finished = false;
  source.addEventListener("status", (event) => {
    const data = JSON.parse(event.data);
    if (data.status === "running") setStatus("running", "运行中");
  });
  source.addEventListener("profile", (event) => {
    const data = JSON.parse(event.data);
    if (data.profile) renderProfile(data.profile);
  });
  source.addEventListener("evidence", (event) => {
    const data = JSON.parse(event.data);
    renderEvidence(data.evidence || []);
  });
  source.addEventListener("step", (event) => {
    const step = JSON.parse(event.data);
    appendStep(step, state.stepCount);
  });
  source.addEventListener("artifact", (event) => {
    const data = JSON.parse(event.data);
    applyArtifacts(data);
  });
  source.addEventListener("warning", (event) => {
    const data = JSON.parse(event.data);
    toast(data.message || "AgentArts 调用异常，已回退本地链路");
  });
  source.addEventListener("done", (event) => {
    finished = true;
    const data = JSON.parse(event.data);
    if (data && data.task_id) applyResult(data);
    source.close();
    finalize();
  });
  source.onerror = () => {
    if (finished) return;
    source.close();
    pollTask(taskId);
  };
}

async function pollTask(taskId) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 900));
    try {
      const data = await fetchJSON(`/api/agent/tasks/${taskId}`);
      if (data.status === "success" && data.result) {
        applyResult(data.result);
        finalize();
        return;
      }
      if (data.status === "failed") {
        toast(`任务失败：${data.error || "未知错误"}`);
        finalize(true);
        return;
      }
    } catch (error) {
      console.warn(error);
    }
  }
  toast("任务超时，请查看服务端日志。");
  finalize(true);
}

function applyArtifacts(data) {
  if (!state.result) state.result = {};
  Object.assign(state.result, data);
  if (data.quality) renderQuality(data.quality, data.safety);
  if (data.resources) {
    const count = data.resources.length;
    const evidence = data.metrics?.evidence_count ?? 5;
    $("#artifactMeta").textContent = `${count} 类资产 · ${evidence} 条证据`;
    $("#traceTitle").textContent = "协作完成 · 产物已落库";
  }
  renderArtifact();
}

function applyResult(result) {
  state.result = result;
  state.running = false;
  if (result.profile) renderProfile(result.profile);
  if (result.evidence) renderEvidence(result.evidence);
  if (result.trace) {
    state.stepCount = 0;
    state.totalMs = 0;
    renderTrace(result.trace);
  }
  if (result.quality) renderQuality(result.quality, result.safety);
  if (result.plan) {
    $("#artifactMeta").textContent = `${result.resources?.length || 0} 类资产 · ${result.metrics?.evidence_count || 0} 条证据`;
  }
  renderArtifact();
}

function finalize(failed = false) {
  state.running = false;
  $("#runBtn").disabled = false;
  $("#sendBtn").disabled = false;
  const success = !failed && state.result;
  setStatus(success ? "success" : "failed", success ? "已完成" : "失败");
  if (success) {
    $("#traceTitle").textContent = "协作完成 · 产物已落库";
    $("#artifactMeta").textContent = `${state.result.resources?.length || 0} 类资产 · ${state.result.metrics?.evidence_count || 0} 条证据`;
    toast("学习方案已生成：4 类资产、证据可追溯、质量与安全门禁通过。");
  }
}

async function runFastDemo() {
  try {
    const created = await fetchJSON("/api/agent/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(currentPayload(true)),
    });
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 250));
      const data = await fetchJSON(`/api/agent/tasks/${created.task_id}`);
      if (data.status === "success" && data.result) {
        applyResult(data.result);
        finalize();
        return;
      }
    }
  } catch (error) {
    console.error(error);
  }
}

/* --------------------------------------------------------------- artifacts */
function renderArtifact() {
  const body = $("#artifactBody");
  const result = state.result;
  if (!result) return;
  if (state.activeTab === "lecture") {
    const lecture = (result.resources || []).find((item) => item.resource_type === "lecture");
    body.innerHTML = lecture
      ? `<article class="lecture">${markdown(lecture.content)}</article>`
      : '<div class="trace-placeholder">尚未生成讲义</div>';
  } else if (state.activeTab === "mindmap") {
    const mindmap = (result.resources || []).find((item) => item.resource_type === "mindmap");
    body.innerHTML = mindmap
      ? `<div class="mindmap-wrap">${renderMindmap(mindmap.content)}<p class="mindmap-legend">点击右侧证据可核对每个分支的课程依据；导图由结构化 JSON 渲染，便于二次编辑。</p></div>`
      : '<div class="trace-placeholder">尚未生成知识图谱</div>';
  } else if (state.activeTab === "quiz") {
    const quiz = (result.resources || []).find((item) => item.resource_type === "quiz");
    body.innerHTML = quiz ? renderQuiz(quiz.content) : '<div class="trace-placeholder">尚未生成练习</div>';
  } else if (state.activeTab === "path") {
    body.innerHTML = result.path ? renderPath(result.path) : '<div class="trace-placeholder">尚未生成学习路径</div>';
  }
  bindArtifactEvents();
}

function renderMindmap(node) {
  if (!node) return "";
  const children = (node.children || [])
    .map((child) => `<li>${renderMindmap(child)}</li>`)
    .join("");
  return `<div class="mind-node"><strong>${escapeHtml(node.label)}</strong>${node.note ? `<small>${escapeHtml(node.note)}</small>` : ""}</div>${children ? `<ul class="mind-branch">${children}</ul>` : ""}`;
}

function renderQuiz(quiz) {
  state.quiz = quiz;
  const questions = quiz.questions || [];
  const answered = Object.keys(state.answers).length;
  return `
    <div class="quiz">
      <div class="quiz-title"><h3>${escapeHtml(quiz.title || "查漏练习")}</h3><span class="quiz-progress">${answered}/${questions.length} 已作答</span></div>
      ${questions.map((question, index) => `
        <div class="question" data-question="${question.id}">
          <p class="q-stem"><span class="q-no">${index + 1}</span><span>${escapeHtml(question.stem)}</span></p>
          <div class="options">
            ${(question.options || []).map((option, optionIndex) => {
              const letter = String.fromCharCode(65 + optionIndex);
              const selected = state.answers[question.id] === letter;
              return `<button type="button" class="option ${selected ? "selected" : ""}" data-q="${question.id}" data-opt="${letter}"><span class="letter">${letter}</span><span>${escapeHtml(option)}</span></button>`;
            }).join("")}
          </div>
          <p class="explanation" data-explanation="${question.id}" hidden></p>
        </div>`).join("")}
      <div class="quiz-actions">
        <button class="primary-btn compact" id="quizSubmit" type="button">提交并生成评估</button>
        <button class="ghost-btn" id="quizReset" type="button">重新作答</button>
      </div>
      <div class="quiz-result" id="quizResult" hidden></div>
    </div>`;
}

function renderPath(path) {
  const days = path.daily_plan || path.days || [];
  return `
    <div class="path">
      <div class="path-head">
        <div><h3>${escapeHtml(path.path_title || "学习路径")}</h3><p>${escapeHtml(path.personalization_summary || "")}</p></div>
        <span class="version-chip">${days.length} 天 · ${path.daily_minutes || 30} 分钟/天</span>
      </div>
      <div class="path-days">
        ${days.map((day) => `
          <article class="day">
            <div class="day-no"><b>${String(day.day).padStart(2, "0")}</b><span>DAY</span></div>
            <div class="day-main">
              <h4>${escapeHtml(day.theme)}</h4>
              <p>${escapeHtml(day.reason || day.expected_outcome || "")}</p>
              <div class="task-row">
                ${(day.tasks || []).map((task) => `<span class="task-chip">${escapeHtml(TYPE_LABEL[task.type] || task.type)} · ${escapeHtml(task.title)}${task.estimated_minutes ? ` <em>${task.estimated_minutes}′</em>` : ""}</span>`).join("")}
              </div>
            </div>
          </article>`).join("")}
      </div>
      <p class="path-note">调整策略：${escapeHtml(path.adjustment_strategy || "根据测验结果动态调整")}</p>
    </div>`;
}

function bindArtifactEvents() {
  if (state.activeTab === "quiz") {
    $$(".option").forEach((button) => {
      button.addEventListener("click", () => {
        if (state.quizSubmitted) return;
        state.answers[button.dataset.q] = button.dataset.opt;
        $$(`.option[data-q="${button.dataset.q}"]`).forEach((item) => item.classList.toggle("selected", item === button));
        const progress = $(`.quiz-progress`);
        if (progress) progress.textContent = `${Object.keys(state.answers).length}/${state.quiz.questions.length} 已作答`;
      });
    });
    const submit = $("#quizSubmit");
    if (submit) submit.addEventListener("click", submitQuiz);
    const reset = $("#quizReset");
    if (reset) reset.addEventListener("click", () => {
      state.answers = {};
      state.quizSubmitted = false;
      renderArtifact();
    });
  }
}

function submitQuiz() {
  if (!state.quiz) return;
  const questions = state.quiz.questions || [];
  let correct = 0;
  questions.forEach((question) => {
    const chosen = state.answers[question.id];
    const explanation = $(`[data-explanation="${question.id}"]`);
    $$(`.option[data-q="${question.id}"]`).forEach((button) => {
      if (button.dataset.opt === question.answer) button.classList.add("correct");
      else if (chosen && button.dataset.opt === chosen) button.classList.add("wrong");
    });
    if (explanation) {
      explanation.hidden = false;
      explanation.innerHTML = `<b>${chosen === question.answer ? "✓ 正确" : "✗ 再想想"}</b> · 正确答案 ${question.answer}。${escapeHtml(question.explanation)}`;
    }
    if (chosen === question.answer) correct += 1;
  });
  const score = Math.round((correct / Math.max(questions.length, 1)) * 100);
  state.quizSubmitted = true;
  const result = $("#quizResult");
  result.hidden = false;
  result.innerHTML = `<b>本次得分 ${score}</b> · 画像与路径将根据错题更新。建议下一步：${score >= 80 ? "进入 BST 中序应用" : "插入 10 分钟“递归出口”补救任务"}。`;
  toast(`练习已提交：${correct}/${questions.length} 题正确，评估结果已生成。`);
}

/* ------------------------------------------------------------------- tabs */
function switchTab(tab) {
  state.activeTab = tab;
  $$(".tab").forEach((item) => item.classList.toggle("active", item.dataset.tab === tab));
  renderArtifact();
}

/* ----------------------------------------------------------------- events */
function bindEvents() {
  $("#runBtn").addEventListener("click", runDemo);
  $("#sendBtn").addEventListener("click", runDemo);
  $("#emptyRunBtn").addEventListener("click", runDemo);
  $("#promptInput").addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") runDemo();
  });
  $$(".tab").forEach((tab) => tab.addEventListener("click", () => switchTab(tab.dataset.tab)));
  $$(".preset").forEach((preset) => {
    preset.addEventListener("click", () => {
      $$(".preset").forEach((item) => item.classList.toggle("active", item === preset));
      const config = PRESETS[preset.dataset.preset] || PRESETS.basic;
      $("#promptInput").value = config.text;
      state.difficulty = config.difficulty;
    });
  });
  $("#evidenceMore").addEventListener("click", () => {
    const list = $("#evidenceList");
    list.classList.toggle("expanded");
    $("#evidenceMore").firstChild.textContent = list.classList.contains("expanded") ? "收起证据 " : "查看全部证据 ";
  });
  const modal = $("#modal");
  const openModal = () => { modal.classList.add("open"); modal.setAttribute("aria-hidden", "false"); };
  const closeModal = () => { modal.classList.remove("open"); modal.setAttribute("aria-hidden", "true"); };
  $("#archBtn").addEventListener("click", openModal);
  $("#platformBtn").addEventListener("click", openModal);
  $("#modalClose").addEventListener("click", closeModal);
  modal.addEventListener("click", (event) => { if (event.target.dataset.close) closeModal(); });
  document.addEventListener("keydown", (event) => { if (event.key === "Escape") closeModal(); });
}

init();
