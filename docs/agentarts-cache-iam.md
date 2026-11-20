# Cache 工作流 IAM 联调

参赛测试环境使用西南-贵阳一的低代码工作流 `edupath-cache-diagnosis-v3`，发布版本为 `ict-cache-api-v1-20261119`。

## 部署前

在 AgentArts 的部署预览核对具体版本和已有额度。选 IAM 入站认证，并核对日志、指标、调用链的费用和开关。首次部署会固定入站鉴权方式。

## 测试环境

在有权限的测试环境中，以服务端环境变量注入以下配置。使用控制台提供的完整 HTTPS 调用路径。默认访问方式如果带有 `?endpoint=Latest`，保留该参数一起签名。签名区域为西南-贵阳一。

| 环境变量 | 取值 |
| --- | --- |
| `EDUPATH_CACHE_DIAGNOSIS_PROVIDER` | `agentarts` |
| `AGENTARTS_CACHE_AUTH_MODE` | `iam` |
| `AGENTARTS_CACHE_ENDPOINT` | 部署后的 `https://…/runtimes/…/invocations` |
| `AGENTARTS_CACHE_IAM_AK` | 测试身份的访问密钥 ID |
| `AGENTARTS_CACHE_IAM_SK` | 测试身份的私有访问密钥 |
| `AGENTARTS_CACHE_IAM_SECURITY_TOKEN` | 使用临时访问密钥时注入对应令牌 |

由凭据持有人在测试环境直接配置。保持服务器时钟同步。默认本地诊断无需这些变量。现有 API Key 环境可沿用原配置。

## 验收

用合成学生账号在 Cache 专项提交四路错答 `20 / 8 / 4`，检查计算步骤和讲义引用。打开二路新题并提交 `21 / 7 / 4`，刷新读取两次记录。核对两次接口响应中的 `feedback.provenance`、服务端请求与执行编号，以及 AgentArts 返回的执行编号。需要录屏时保留部署状态与两次作答画面，遮挡凭据和账号信息。

最近一次联调记录确认实例状态正常且管理接口可读，正式发布接口仍返回 HTTP 401。控制台试运行、实例部署和产品直调是三项独立验证。
