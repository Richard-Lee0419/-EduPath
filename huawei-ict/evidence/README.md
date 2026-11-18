# 证据与截图

> **提交材料的产品界面使用 `live-ui/` 下的原项目线上实例截图**（2026-10-28，https://8.136.194.87/）。
> `demo-*.png` 是自研 Demo 的技术备份，`original-ui/` 是旧素材备份，均不进入 docx/PPT/视频。
> 平台截图需在 AgentArts 开通后补。

## 文件

### 原项目线上实例（提交用）

| 文件 | 内容 | 来源 |
| --- | --- | --- |
| `live-ui/home.png` | 学习首页 | 线上实例实拍 |
| `live-ui/path.png` | 学习路径 | 线上实例实拍 |
| `live-ui/evaluation.png` | 学习评估 67% | 线上实例实拍 |
| `live-ui/resources.png` | 资源生成全页 | 线上实例实拍 |
| `live-ui/resource-top.png` | 资源生成/质量监测 | 线上实例实拍 |
| `live-ui/profile.png` | 画像对话/档案 | 线上实例实拍 |
| `live-ui/home-crop.png` | PPT 16:10 裁切 | PPT 第 9 页 |
| `live-ui/resource-crop.png` | PPT 16:10 裁切 | PPT 第 9 页 |
| `live-ui/path-crop.png` | PPT 16:10 裁切 | PPT 第 9 页 |
| `live-ui/docx-showcase-live.png` | 首页 + 评估合成图 | 信息表“作品展示” |

### 旧素材备份（不进入提交材料）

| 文件 | 内容 | 来源 |
| --- | --- | --- |
| `original-ui/orig-home.png` | 学习首页（旧基线） | 原项目历史截图 |
| `original-ui/orig-resource.png` | 资源生成（旧基线） | 原项目历史截图 |
| `original-ui/docx-showcase-original.png` | 旧版合成图 | 旧素材 |

### 自研 Demo 备份（不进入提交材料）

| 文件 | 内容 | 来源 |
| --- | --- | --- |
| `demo-home.png` | 首页：画像 / 任务 / 轨迹 / 质量 / 证据 | `?demo=done` |
| `demo-lecture.png` | 定制讲义 + 引用 + 证据侧栏 | `?demo=done&full=1&tab=lecture` |
| `demo-mindmap.png` | 知识图谱 | `?demo=done&full=1&tab=mindmap` |
| `demo-quiz.png` | 3 题查漏练习 | `?demo=done&full=1&tab=quiz` |
| `demo-path.png` | 三日学习路径 | `?demo=done&full=1&tab=path` |
| `selfcheck.json` | 16 项离线自检结果 | `python scripts/selfcheck.py` |

## 复现截图

```bash
cd huawei-ict
python -m bridge.gateway --port 8765
# 浏览器打开对应 URL，截 1600px 宽视口
```

或用 Edge 无头（Windows）：

```powershell
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
& $edge --headless=new --no-sandbox --disable-gpu --hide-scrollbars `
  --window-size=1600,2400 --virtual-time-budget=12000 `
  --screenshot="evidence/demo-lecture.png" `
  "http://127.0.0.1:8765/?demo=done&full=1&tab=lecture"
```

## 重新生成信息表

```bash
python scripts/build_submission_docx.py
```

脚本会使用 `docx-showcase.png`；如替换截图，先重新合成该图。

## 尚未提供的证据

- AgentArts 知识库列表 / 命中测试截图
- 工作流画布与版本记录
- MCP 工具发现（10 个）
- 平台评测 24 条结果
- 3.5 分钟实机视频

这些必须在平台账号内真实操作后补齐，不能用本地截图替代。
