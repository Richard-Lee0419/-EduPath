# 证据与截图

> 所有截图均来自 `huawei-ict` 本地 `fixture` 彩排模式，页面右上角明确标注“本地演示 · 未上云”。
> 正式提交前，请用 AgentArts 真实端点重跑并替换；不要把这些截图说成平台运行结果。

## 文件

| 文件 | 内容 | 来源 |
| --- | --- | --- |
| `demo-home.png` | 首页：画像 / 任务 / 轨迹 / 质量 / 证据 | `?demo=done` |
| `demo-lecture.png` | 定制讲义 + 引用 + 证据侧栏 | `?demo=done&full=1&tab=lecture` |
| `demo-mindmap.png` | 知识图谱 | `?demo=done&full=1&tab=mindmap` |
| `demo-quiz.png` | 3 题查漏练习 | `?demo=done&full=1&tab=quiz` |
| `demo-path.png` | 三日学习路径 | `?demo=done&full=1&tab=path` |
| `docx-showcase.png` | 信息表“作品展示”用双图合成 | `demo-home` + `demo-quiz` 等高等宽 |
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
