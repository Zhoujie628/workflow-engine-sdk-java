# A2A-T SDK Negotiation-T 适配说明

> 状态：已按 2026-08-25 锁定的 SDK 版本完成。本文只描述当前实现，不保留旧版迁移方案。

## 版本基线

- 仓库：`Zhoujie628/a2a-t-sdk-java`
- commit：`0ef79d37f49a9b7a2dbe16b6d9fd1ccdb6d9538d`
- Maven 版本：`1.0.0-0ef79d3`
- 引擎入口：`A2ATContentFacade`、`NegotiationTHandler`、`DefaultWorkflowEngineClient`
- Agent 入口：`NegotiationBaseAgentExecutor`

该 SDK 尚未发布到 Maven Central。引擎 POM 和 CI 同时锁定 commit 与由该提交派生的
不可变版本，避免本地旧 `1.0.0` jar 被静默解析。安装与升级方法见
[A2A-T SDK 依赖说明](A2AT-SDK-DEPENDENCY.md)。

## 当前 API 边界

最新 SDK 的 Negotiation-T 是无状态内容 API。引擎使用：

- `generateNegotiationProposePromptFromData/FromText`
- `generateNegotiationAcceptPromptFromData/FromText`
- `generateNegotiationRejectPromptFromData/FromText`
- `generateNegotiationAbortPromptFromData`
- `validateAndFillingProposeData/AcceptData/RejectData`
- `getNegotiationPrompts/getNegotiationPrompt`

旧协商运行时状态机接口已从 SDK 删除，引擎不兼容、不反射调用、不降级回该类接口。

## 协商上下文与 metadata

每次协商由引擎持有 `NegotiationContext`：

```json
{
  "id": "3dbc13b5-bd57-4c2b-b503-24e381b6c8d3",
  "round": 1,
  "maxRounds": 5
}
```

线上 metadata 必须同时包含：

- 规范 Negotiation-T URI：
  `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1`
- SDK 生成的 `templateUri`
- 规范 `negotiationContext`

上下文不再嵌入 prompt 正文。接收端会检查 UUID、轮次与最大轮次，检查
`templateUri` 是否属于对应 propose/accept/reject 阶段，再调用 SDK 校验与参数填充。

## 内容生成与 LLM

- Negotiation-T 类型化 `fromData` 直接接收 SDK record，是确定性模板渲染，不调用 LLM。
- Negotiation-T `fromText` 需要 SDK 配置的 LLM 进行结构化抽取。
- `validateAndFilling*` 包含确定性规则门、LLM 语义校验/参数抽取和确定性合并。
- Task-T、Authorization-T、Notification-T 的 schema-aware `fromData` 会跳过场景识别，
  但 slot 映射可能使用 LLM；不应宣称为“全程无 LLM”。

## 端到端流程

1. OMC 对 Task-T 进行 SDK 校验与填充。仅缺失或业务语义错误进入 Negotiation-T；
   LLM/配置/网络等基础设施错误不得伪装成业务协商。
2. OMC 用 typed propose data 渲染 prompt，在 A2A `INPUT_REQUIRED` 事件中返回 SDK metadata。
3. `NegotiationTHandler` 校验 URI、模板、上下文和 prompt，将已填充参数交给集成方的
   `ControlPoint.onNegotiation` 作业务决策。
4. 引擎把决策映射为 typed accept/reject/abort，推进 `round`，通过同一 A2A task/context
   发送 follow-up。
5. 轮次耗尽、明确拒绝或校验失败时终止，不使用手工 prompt 或原文 metadata 降级。

## 失败策略

协议内容统一 fail-closed。如果 SDK 客户端未配置、渲染失败、模板 URI 不匹配、
`negotiationContext` 非法或语义校验拒绝，引擎会显式失败，不把普通文本冒充为
Negotiation-T 内容。

## 验证基线

- `A2ATContentFacadeTest`：真实 SDK typed propose/accept/reject/abort 渲染和 metadata 形状。
- `SpnCrossCityE2ETest`：真实 SDK Task-T/Negotiation-T/Authorization-T/Notification-T，
  两地市并行诊断、单次汇总、白名单抢通和通知关闭。
- `EmbeddedA2AServerTest`：HTTPS + SSE 真实网络路径。
- SDK 不可用、模板错误、校验拒绝和通知失败 ACK 的反向用例。
