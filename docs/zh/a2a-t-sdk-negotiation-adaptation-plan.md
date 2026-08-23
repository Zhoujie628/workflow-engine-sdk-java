# 执行引擎适配 A2A-T SDK 协商内容层改造方案

> **状态（2026-08-23）：改造一 ~ 六已全部落地。** 引擎侧入口见 `A2ATContentFacade`（SDK 内容层门面）、
> `WorkflowEngineClient.getPrompts/getNegotiationPrompts/getPrompt`（模板查询）、
> `WorkflowEngineClientConfig.negotiationParamSchema`（业务 schema 注入）。本文档以下内容保留为
> 设计依据；文中 API 签名是改造前的 SDK 形态，**当前签名以
> `docs/zh/API_REFERENCE.md` 为准**（后续 SDK 已将 `NegotiationContext` 移至
> `core.model`、validate API 增加显式 context 参数、协商上下文改由 metadata `negotiationContext`
> key 携带）。

> 本文档基于对 a2a-t-sdk-java（v1.0.0）和 workflow-exec-engine-java（v1.0.0）最新代码的逐文件分析，给出执行引擎接入 SDK 协商内容生成与校验能力的详细改造方案。

---

## 一、现状：执行引擎当前用了 SDK 的什么

执行引擎目前只用了 SDK 的 **运行时状态机**（startNegotiation / receiveNegotiation / continueNegotiation 三个方法），完全没有用 **内容生成引擎**（generateNegotiationProposePromptFromData/fromText 等 6 个方法）和 **校验参数提取**（validateAndFillingProposeData/AcceptData/RejectData）这两条流水线。

### 1.1 当前对接链路

**Client 侧（引擎发起方）**

```
DefaultWorkflowEngineClient.sendMessage()
  -> transport.send()                          # A2A 消息发到 agent
  -> NegotiationTHandler.afterReceive()         # agent 返回 INPUT_REQUIRED 时触发
    -> a2atClient.receiveNegotiation()           # SDK 状态机：解析 context + echo 处理
  -> autoNegotiate()                             # 自动循环
    -> controlPoint.onNegotiation()              # 生成澄清文本（纯业务逻辑，非 SDK）
    -> a2atClient.continueNegotiation()          # SDK 状态机：推进到 AGREED
    -> buildFallbackMeta(clarification)         # 手动组装 metadata（非 SDK 模板）
    -> transport.send()                          # 发 follow-up 给 agent
```

**Server 侧（agent 执行器）**

```
NegotiationBaseAgentExecutor.handleNewTask()
  -> client.startNegotiation(TARGET, text, facts)   # SDK 状态机：发起协商
  -> 从 payload 手动提取 context + text               # 非 SDK 封装
  -> negotiationResponseMetadata()                    # 手动组装 metadata
  -> emit INPUT_REQUIRED
```

### 1.2 当前协商消息的内容来源

协商消息的文本内容不是 SDK 生成的，是业务代码硬编码或 LLM 直接输出的。具体在三个地方：

1. **发起侧**（NegotiationBaseAgentExecutor）：defaultNegotiationText() 返回固定字符串如 "Please confirm the task parameters so I can proceed."，直接塞进 startNegotiation 的 contentText 参数。SDK 的 startNegotiation 只做状态机初始化，不做模板渲染。

2. **澄清侧**（DefaultControlPoint.onNegotiation / NegotiationStrategy.resolve）：返回纯文本 clarification，不走 SDK 的内容生成。autoNegotiate 把它包进 [NEGOTIATION_RESOLUTION] 格式的字符串，这个格式是引擎自己定义的（NegotiationUtils.buildResolutionMessage），不是 SDK 的协商模板格式。

3. **fallback metadata**（buildFallbackMeta）：当 continueNegotiation 失败时，用 A2ATExtension.NEGOTIATION_T.uri() 做 key，直接拼字符串。

### 1.3 依赖声明

引擎在 pom.xml 中声明：

```xml
<properties>
    <a2a.t.sdk.version>1.0.0</a2a.t.sdk.version>
</properties>

<dependency>
    <groupId>net.openan.a2a-t.sdk</groupId>
    <artifactId>a2a-t-client</artifactId>
    <version>${a2a.t.sdk.version}</version>
</dependency>
```

a2a-t-client 通过传递依赖引入了 a2a-t-negotiation 模块，内容生成引擎和校验流水线已可用，无需额外加依赖。

---

## 二、差距：SDK 已有但引擎未用的能力

SDK 的 NegotiationContentService（通过 A2ATClient 暴露）提供了完整的协商内容生成和校验能力，引擎完全没有调用。

### 2.1 内容生成引擎（未接入）

SDK 有 6 个生成方法，覆盖 3 种协商类型 x 2 种输入方式 x 3 个阶段：

| 方法 | 作用 | LLM 依赖 | 引擎是否调用 |
|---|---|---|---|
| generateNegotiationProposePromptFromData | 从类型化数据生成提议消息 | 无 | 否 |
| generateNegotiationProposePromptFromText | 从自由文本生成提议消息 | 有（一步抽取） | 否 |
| generateNegotiationAcceptPromptFromData | 从类型化数据生成接受消息 | 无 | 否 |
| generateNegotiationAcceptPromptFromText | 从自由文本生成接受消息 | 有 | 否 |
| generateNegotiationRejectPromptFromData | 从类型化数据生成拒绝消息 | 无 | 否 |
| generateNegotiationRejectPromptFromText | 从自由文本生成拒绝消息 | 有 | 否 |

这些方法返回 MetadataContent(templateUri, promptText, extensionUri)，调用 buildMetadataContent() 直接生成可放入 A2A 消息的 metadata map，引擎不需要手动组装。

### 2.2 类型化内容模型（未使用）

SDK 定义了完整的类型化内容 record，引擎可以用这些结构化数据驱动协商，而不是用裸字符串：

**Propose 阶段内容**

| 类型 | 结构 | 适用场景 |
|---|---|---|
| InfoProposeContent | items: List<NegotiationItem>, relationship: String? | 信息协商：缺失信息项列表 |
| TargetProposeContent | targetNegotiationDescription, intentUnderstanding?, alignmentAndClarification?, requestForClarification? | 目标协商：目标描述 + 意图理解 + 对齐澄清 + 待澄清 |
| FeasibilityProposeContent | feasibilityNegotiationDescription, action, contentsToEvaluate?, infeasibilityDetailsAndProposal? | 可行性协商：描述 + 动作 + 评估内容 / 不可行详情 |

**Ending 阶段内容**

| 类型 | 结构 | 适用场景 |
|---|---|---|
| InfoEndingContent | conclusion, items | 信息协商终态 |
| TargetEndingContent | conclusion, confirmedIntent, failureReason | 目标协商终态（accept 带 confirmedIntent，reject 带 failureReason） |
| FeasibilityEndingContent | conclusion, feasibilitySummary | 可行性协商终态 |

FeasibilityProposeContent 有一个 NegotiationAction 字段，两种取值：
- REQUEST_FEASIBILITY_EVALUATION —— 请求对端评估可行性，必须带 contentsToEvaluate
- PROPOSE_ALTERNATIVE_ON_FAILURE —— 报告不可行并提议替代方案，必须带 infeasibilityDetailsAndProposal

当前引擎用的是 NegotiationType.TARGET，但 TargetNegotiation handler 是空壳 echo，协商消息内容是 defaultNegotiationText() 硬编码的。

### 2.3 校验与参数提取（未接入）

SDK 的 validateAndFillingProposeData/AcceptData/RejectData 可以校验已渲染的协商消息文本并提取参数。管线分为三步：

1. **规则门**（确定性，无 LLM）：按 ## 分段，识别是否含协商上下文段，校验 id 为 UUID 格式、round/maxRounds 为正整数、round 不超 maxRounds
2. **LLM 语义校验**（可重试）：一步 LLM 结构化调用，同时产出 verdict（通过/拒绝）、implied type、语义错误列表、按 callerSchema 提取的参数
3. **参数合并**（确定性）：先写 context 参数（id、round、maxRounds），再写 LLM 提取参数，冲突时 context 参数优先

引擎在 NegotiationTHandler.afterReceive 里接收协商消息时没有做内容校验，直接交给 receiveNegotiation echo 处理。

### 2.4 模板查询（未接入）

SDK 的 getNegotiationPrompts() / getNegotiationPrompt(uri) 可以列出和查询可用的协商模板。引擎没有用这个能力，模板选择是隐式的（硬编码在 startNegotiation 调用里）。

### 2.5 Vocabulary 多语言（未使用）

SDK 的 Vocabulary 支持 zh-CN 和 en-US，协商消息的 section 标题和 slot 名都从 vocabulary 取。引擎的 .env 配了 A2AT_LANGUAGE=zh-CN，但协商消息内容没有走模板渲染，所以这个语言设置实际只作用于 Task-T 侧（场景识别和 slot 抽取），没作用于协商消息。

---

## 三、改造方案

改造分三个层次，按依赖关系排序。

### 改造一：协商消息发起层 —— 用内容生成替代硬编码

> ✅ 已落地：`NegotiationBaseAgentExecutor.renderProposeText` + 子类扩展点 `negotiationType()` / `buildProposeContent()` / `proposeTemplateUri()`。


**目标**：server 侧发起协商时，用 SDK 的模板渲染生成结构化协商消息，而不是 defaultNegotiationText() 硬编码。

**当前代码路径**

- samples/.../NegotiationBaseAgentExecutor.java 的 startNegotiation 方法
- 调用 client.startNegotiation(NegotiationType.TARGET, defaultNegotiationText(), facts)
- defaultNegotiationText() 返回固定字符串

**改造方式**

把 startNegotiation 拆成两步 —— 先用内容生成引擎渲染消息，再用状态机初始化协商上下文：

```java
// 步骤 1：用 SDK 内容生成引擎渲染结构化协商消息
NegotiationContext contentCtx = new NegotiationContext(
    UUID.randomUUID().toString(), 1, NegotiationContext.DEFAULT_MAX_ROUNDS);
TargetProposeContent content = new TargetProposeContent(
    targetDescription,                  // targetNegotiationDescription（必填）
    intentUnderstandingItems,           // intentUnderstanding（首轮有，后续 null）
    null,                               // alignmentAndClarification（后续轮有，首轮 null）
    requestForClarificationItems        // requestForClarification（待澄清项）
);
NegotiationProposeData data = new NegotiationProposeData(contentCtx, content);
MetadataContent mc = client.generateNegotiationProposePromptFromData(
    data, "Negotiation-T/v1/target-negotiation/propose");

// 步骤 2：用渲染好的消息文本初始化协商状态机
Map<String, Object> facts = new LinkedHashMap<>();
facts.put("agent", getClass().getSimpleName());
facts.put("input", input);
Map<String, Object> neg = client.startNegotiation(
    NegotiationType.TARGET, mc.promptText(), facts);

// 步骤 3：把生成的 metadata 和状态机 payload 合并
Map<String, Object> metadata = mc.buildMetadataContent();
metadata.putAll(neg);  // 协商 context 附在消息上
```

这样 agent 返回的 INPUT_REQUIRED 消息就是 SDK 模板渲染的结构化文本（带 ## Negotiation Context、## Target Negotiation 等 section），而不是 "Please confirm the task parameters" 这种裸字符串。

**改造范围**

NegotiationBaseAgentExecutor 需要新增一个抽象方法让子类提供 TargetProposeContent（或 InfoProposeContent / FeasibilityProposeContent），替代 defaultNegotiationText()：

```java
// 新增抽象方法
protected abstract NegotiationProposeContent buildProposeContent(String input);

// 保留旧方法做降级
protected String defaultNegotiationText() { ... }
```

**fromText 变体**

如果子类不想构造类型化数据，可以用 generateNegotiationProposePromptFromText 走 LLM 抽取：

```java
MetadataContent mc = client.generateNegotiationProposePromptFromText(
    naturalLanguageDescription, contentCtx,
    "Negotiation-T/v1/target-negotiation/propose");
```

这会多一步 LLM 调用（结构化抽取），但调用方只需提供自由文本。

### 改造二：协商自动循环层 —— 用内容生成替代手动组装

> ✅ 已落地：`DefaultWorkflowEngineClient.buildNegotiationFollowUpMeta`（含 Reject/Abort 终态分类与 SDK 状态机推进）。


**目标**：client 侧 autoNegotiate 生成 follow-up 消息时，用 SDK 的内容生成替代 buildFallbackMeta 手动拼字符串。

**当前代码路径**

- workflow-engine/.../DefaultWorkflowEngineClient.java 的 autoNegotiate 和 buildNegotiationFollowUpMeta 方法
- buildNegotiationFollowUpMeta 尝试调 continueNegotiation，失败时用 buildFallbackMeta 拼字符串

**改造方式**

在 autoNegotiate 的 clarification 生成后，用 SDK 渲染 follow-up 消息：

```java
A2ATClient a2atClient = transport.getA2atClient();
if (a2atClient != null) {
    // 用 SDK 生成 propose 阶段的 follow-up 消息
    NegotiationContext contentCtx = new NegotiationContext(
        negotiationId, round, maxRounds);
    // clarification 作为 TargetProposeContent 的 alignmentAndClarification
    TargetProposeContent content = new TargetProposeContent(
        "Clarification for " + agentName,
        null,                                                // intentUnderstanding（非首轮）
        List.of(new NegotiationItem("clarification", clarification)),  // alignment
        null                                                 // requestForClarification
    );
    NegotiationProposeData data = new NegotiationProposeData(contentCtx, content);
    MetadataContent mc = a2atClient.generateNegotiationProposePromptFromData(
        data, "Negotiation-T/v1/target-negotiation/propose");

    // SDK continueNegotiation 推进状态机
    Map<String, Object> payload = a2atClient.continueNegotiation(
        context, NegotiationStatus.AGREED, mc.promptText());

    // metadata 直接用 SDK 生成
    followUpMeta = new HashMap<>(mc.buildMetadataContent());
    followUpMeta.putAll(payload);
} else {
    // 无 SDK 时降级到旧的 fallback 逻辑
    followUpMeta = buildFallbackMeta(clarification);
}
```

这样 follow-up 消息也是模板渲染的结构化文本，可以逐步替代 [NEGOTIATION_RESOLUTION] 这种自定义标记格式。

**协商策略接口改造**

NegotiationStrategy.resolve 当前返回 CompletableFuture<String>（纯文本 clarification）。改造后可以增加一个变体返回类型化内容：

```java
interface NegotiationContentStrategy {
    CompletableFuture<NegotiationProposeContent> resolveContent(
        String agentName, String negotiationText, Map<String, Object> receiveResult);
}
```

DefaultControlPoint 持有这个策略，在 onNegotiation 里调用它获取类型化内容，交给 SDK 渲染。不愿意构造类型化数据的用户仍用旧的 resolve 返回纯文本，走 fromText 变体。

### 改造三：协商消息接收校验层 —— 用 validateAndFilling 替代裸 receive

> ✅ 已落地：`NegotiationTHandler.afterReceive` 按类型路由 validate 模板；业务 schema 经 `negotiationParamSchema` 配置注入（引擎核心不再硬编码业务字段）。


**目标**：NegotiationTHandler.afterReceive 接收协商消息后，用 SDK 校验消息格式并提取参数，而不是只做 echo。

**当前代码路径**

- workflow-engine/.../NegotiationTHandler.java 的 afterReceive
- 调用 a2atClient.receiveNegotiation(result.getText(), contextMap)
- SDK 的 receiveNegotiation 只做状态机轮次校验 + echo（TargetNegotiation handler 是空壳）

**改造方式**

在 receiveNegotiation 之后追加一步 validateAndFillingProposeData：

```java
Map<String, Object> receiveResult =
    a2atClient.receiveNegotiation(result.getText(), contextMap);

if (Boolean.TRUE.equals(receiveResult.get("needResponse"))) {
    try {
        FilledParamData paramData = a2atClient.validateAndFillingProposeData(
            result.getText(),
            callerSchema,    // 调用方提供的参数 JSON schema
            "Negotiation-T/v1/target-negotiation/propose"
        );
        // 提取的参数可用于后续路由决策
        metadata.put("negotiation_params", paramData.data());
    } catch (NegotiationParamExtractionException e) {
        // 校验失败：消息不是合法的协商消息，或语义校验拒绝
        log.warn("[Negotiation-T] validation failed: code={} msg={}",
            e.getCode(), e.getMessage());
    }
}
```

这需要 NegotiationTHandler 能拿到 callerSchema（参数 schema）。可以通过 ExtensionHandler 接口扩展一个 schema provider，或者从 AgentCard 的扩展声明里读取。

**无 LLM 降级**

validateAndFillingProposeData 的管线是：规则门（确定性，无 LLM）-> LLM 语义校验 -> 参数合并。其中 LLM 语义校验需要配置 LLM client。如果 .env 里没配 LLM，语义校验会失败并返回 negotiation_llm_infrastructure_error。规则门部分（识别协商上下文段、校验 id/round/maxRounds）不需要 LLM，可以独立工作。

### 改造四：协商终态消息 —— 接入 accept/reject 生成

> ✅ 已落地：Accept/Reject/Abort 三终态 + 轮次耗尽 abort 流程。


**目标**：协商结束时（AGREED 或 REJECTED），用 SDK 生成终态消息，而不是靠 continueNegotiation 的 echo。

**当前代码路径**

- DefaultWorkflowEngineClient.autoNegotiate 调用 continueNegotiation(context, NegotiationStatus.AGREED, clarification)
- SDK 的 InformationNegotiation.renderContinue 在 AGREED 时产出 finalTaskPrompt，但 TargetNegotiation 没有这个逻辑

**改造方式**

在协商达成一致或拒绝时，用 SDK 生成终态消息：

```java
// 协商达成一致
NegotiationEndingData acceptData = new NegotiationEndingData(
    contentCtx,
    new TargetEndingContent(NegotiationConclusion.ACCEPT, confirmedIntent, null)
);
MetadataContent acceptMc = a2atClient.generateNegotiationAcceptPromptFromData(
    acceptData, "Negotiation-T/v1/target-negotiation/accept-reject");

// 协商拒绝
NegotiationEndingData rejectData = new NegotiationEndingData(
    contentCtx,
    new TargetEndingContent(NegotiationConclusion.REJECT, null, failureReason)
);
MetadataContent rejectMc = a2atClient.generateNegotiationRejectPromptFromData(
    rejectData, "Negotiation-T/v1/target-negotiation/accept-reject");
```

### 改造五：协商类型选择 —— 从单一 TARGET 扩展到三种

> ✅ 已落地：三种类型全部支持（发送侧子类声明、接收侧按 `negotiationType` 路由模板）。


**目标**：根据业务场景选择合适的协商类型，而不是固定用 TARGET。

**当前代码**

NegotiationBaseAgentExecutor.startNegotiation 硬编码 NegotiationType.TARGET。

**改造方式**

新增抽象方法让子类声明协商类型和对应的 content 类型：

```java
protected abstract NegotiationType negotiationType();
protected abstract NegotiationProposeContent buildProposeContent(String input);
```

各场景的映射建议：

| 业务场景 | 协商类型 | content 类型 | 适用时机 |
|---|---|---|---|
| 参数不足，需补充信息 | INFORMATION | InfoProposeContent | agent 缺少执行所需字段 |
| 意图理解偏差，需对齐 | TARGET | TargetProposeContent | agent 和 engine 对任务目标理解不一致 |
| 无法执行，需评估可行性 | FEASIBILITY | FeasibilityProposeContent | agent 不确定能否完成请求 |

### 改造六：模板查询与动态选择

> ✅ 已落地：`WorkflowEngineClient.getPrompts/getNegotiationPrompts/getPrompt`。


**目标**：运行时查询可用模板，动态选择 templateUri，而不是硬编码。

```java
// 列出所有可用协商模板
List<PromptTemplate> templates = a2atClient.getNegotiationPrompts();
// 查询特定模板
Optional<PromptTemplate> template = a2atClient.getNegotiationPrompt(
    "Negotiation-T/v1/target-negotiation/propose");
```

这可以在 NegotiationBaseAgentExecutor 初始化时调用，把可用模板列表暴露给子类做选择。

---

## 四、改造优先级

| 优先级 | 改造 | 价值 | 风险 | 改动模块 |
|---|---|---|---|---|
| P0 | 改造一：发起层用内容生成 | 协商消息从裸字符串变为结构化模板文本，是后续所有改造的基础 | 低，只改 sample 侧 | samples |
| P0 | 改造二：自动循环用内容生成 | follow-up 消息标准化，逐步消除 [NEGOTIATION_RESOLUTION] 自定义格式 | 中，改 DefaultWorkflowEngineClient 核心链路 | workflow-engine |
| P1 | 改造三：接收校验 | 接收侧能识别非法协商消息、提取参数做路由 | 中，依赖 LLM 配置，需处理无 LLM 降级 | workflow-engine |
| P1 | 改造五：协商类型扩展 | 覆盖三种协商场景 | 低，渐进式扩展 | samples + workflow-engine |
| P2 | 改造四：终态消息 | accept/reject 消息标准化 | 低 | workflow-engine |
| P2 | 改造六：模板查询 | 动态选择模板 | 低 | samples |

建议实施顺序：先改 sample 验证（改造一），再改核心链路（改造二），然后渐进扩展（改造三/五），最后收尾（改造四/六）。

---

## 五、关键约束和注意事项

### 5.1 SDK 版本

引擎 pom.xml 声明 a2a.t.sdk.version=1.0.0，SDK 的内容生成 API 在这个版本已完整可用。引擎只需确保 a2a-t-client 依赖引入了 a2a-t-negotiation 模块（传递依赖已覆盖）。

### 5.2 LLM 依赖

- fromData 变体不需要 LLM（确定性渲染）
- fromText 变体需要 LLM（结构化抽取一步）
- validateAndFilling 的语义校验步骤需要 LLM，规则门步骤不需要

改造时应在无 LLM 环境下做降级处理（a2at.llm.disabled 系统属性已在 sample 里使用）。无 LLM 时走旧的 echo/fallback 路径即可。

### 5.3 两套 NegotiationContext

SDK 有两个 NegotiationContext：

| 类 | 用途 | 字段 |
|---|---|---|
| net.openan.a2at.sdk.negotiation.content.NegotiationContext | 内容生成/校验 | id, round, maxRounds |
| net.openan.a2at.sdk.negotiation.types.model.NegotiationContext | 状态机 | negotiationType, negotiationId, round, status |

改造时要注意传入正确的那一个：内容生成方法用 content 层的，状态机方法用 types.model 层的。两者不能混用。

### 5.4 Vocabulary 语言

SDK 的 Vocabulary.forLanguage 只支持 zh-CN 和 en-US，不支持的 language 会抛 NegotiationContentException。引擎的 .env 已配 A2AT_LANGUAGE=zh-CN，但内容生成引擎用的是 A2ATConfig.prompt.language，不是 A2AT_LANGUAGE 直接传给 vocabulary。需要确认 config 传递链路正确。

### 5.5 错误码处理

内容生成和校验的异常体系：

| 异常类型 | 性质 | 处理方式 |
|---|---|---|
| NegotiationContentException | 编程错误（null context、畸形 URI、类型不匹配） | 不该 catch 后忽略，应修复调用代码 |
| NegotiationGenerationException | 处理失败，带 error code | try-catch 降级到旧路径 |
| NegotiationParamExtractionException | 校验失败，带 error code | try-catch 降级到旧路径 |

error code 体系：

| 码 | 含义 | 可重试 |
|---|---|---|
| template_not_found | 模板/prompt 资源缺失 | 否 |
| negotiation_content_extract_failed | LLM 内容抽取响应不可解析 | 是 |
| negotiation_llm_infrastructure_error | LLM 调用基础设施失败 | 是 |
| negotiation_slot_missing | 必填字段缺失 | 否 |
| negotiation_invalid_input | 输入与 phase/action 矛盾 | 否 |
| negotiation_rule_violation | 规则门校验失败 | 否 |
| negotiation_semantic_rejected | 语义校验拒绝 | 否 |

LLM 步骤默认重试 3 次（maxAttempts 来自 LLM config）。

### 5.6 状态机和内容层是解耦的

SDK 的 startNegotiation/receiveNegotiation/continueNegotiation 只管状态机轮次，generateNegotiationProposePromptFromData 只管消息渲染。两者通过以下方式关联：

1. 内容生成方法的 NegotiationContext（content 层，含 id/round/maxRounds）与状态机方法的 context 是独立的
2. 内容生成方法返回的 promptText 作为状态机方法的 contentText 参数传入
3. 改造时先调内容生成拿到 promptText，再把 promptText 传给状态机方法做 contentText 参数

### 5.7 改造影响面

| 改造 | 改的类 | 模块 | 是否影响核心链路 |
|---|---|---|---|
| 改造一 | NegotiationBaseAgentExecutor | samples | 否 |
| 改造二 | DefaultWorkflowEngineClient | workflow-engine | 是 |
| 改造三 | NegotiationTHandler（包级私有，需改 public 或扩展接口） | workflow-engine | 是 |
| 改造四 | DefaultWorkflowEngineClient | workflow-engine | 是 |
| 改造五 | NegotiationBaseAgentExecutor + DefaultControlPoint | samples + workflow-engine | 部分 |
| 改造六 | NegotiationBaseAgentExecutor | samples | 否 |

建议先改 sample 验证（改造一、六），再改核心链路（改造二、三），然后渐进扩展（改造四、五）。

---

## 六、SDK 协商 API 速查

### 6.1 运行时状态机（引擎当前已用）

```java
// 发起协商
Map<String, Object> startNegotiation(NegotiationType type, String contentText, Map<String, Object> facts);

// 接收对端协商消息
Map<String, Object> receiveNegotiation(String message, Map<String, Object> context);

// 本地推进协商
Map<String, Object> continueNegotiation(
    NegotiationContext context, NegotiationStatus status, String contentText);
```

### 6.2 内容生成（改造后接入）

```java
// 从类型化数据生成（无 LLM）
MetadataContent generateNegotiationProposePromptFromData(NegotiationProposeData data, String templateUri);
MetadataContent generateNegotiationAcceptPromptFromData(NegotiationEndingData data, String templateUri);
MetadataContent generateNegotiationRejectPromptFromData(NegotiationEndingData data, String templateUri);

// 从自由文本生成（含 LLM 抽取）
MetadataContent generateNegotiationProposePromptFromText(String text, NegotiationContext context, String templateUri);
MetadataContent generateNegotiationAcceptPromptFromText(String text, NegotiationContext context, String templateUri);
MetadataContent generateNegotiationRejectPromptFromText(String text, NegotiationContext context, String templateUri);
```

### 6.3 校验与参数提取（改造后接入）

```java
FilledParamData validateAndFillingProposeData(String prompt, Map<String, Object> schema, String templateUri);
FilledParamData validateAndFillingAcceptData(String prompt, Map<String, Object> schema, String templateUri);
FilledParamData validateAndFillingRejectData(String prompt, Map<String, Object> schema, String templateUri);
```

### 6.4 模板查询（改造后接入）

```java
List<PromptTemplate> getNegotiationPrompts();
Optional<PromptTemplate> getNegotiationPrompt(String uri);
List<PromptTemplate> getPrompts();
Optional<PromptTemplate> getPrompt(String uri);
```

### 6.5 模板 URI 格式

```
Negotiation-T/v1/{type-segment}/{phase-segment}
```

| 场景 | URI |
|---|---|
| 信息协商提议 | Negotiation-T/v1/information-negotiation/propose |
| 信息协商接受/拒绝 | Negotiation-T/v1/information-negotiation/accept-reject |
| 目标协商提议 | Negotiation-T/v1/target-negotiation/propose |
| 目标协商接受/拒绝 | Negotiation-T/v1/target-negotiation/accept-reject |
| 可行性协商提议 | Negotiation-T/v1/feasibility-negotiation/propose |
| 可行性协商接受/拒绝 | Negotiation-T/v1/feasibility-negotiation/accept-reject |

accept 和 reject 共享 accept-reject 模板，由 NegotiationConclusion 值（Accept/Reject）区分。

### 6.6 扩展 URI 常量

| 扩展 | URI |
|---|---|
| Negotiation-T（规范） | https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1 |
| Negotiation-T（NL legacy） | https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/NL/v1 |

SDK 用规范 URI 发送新消息，接收侧同时识别 NL 别名以兼容旧消息。

---

## 七、参考文件索引

### SDK 侧（a2a-t-sdk-java）

| 文件 | 作用 |
|---|---|
| a2a-t-client/.../A2ATClient.java | 对外 facade，暴露全部协商 API |
| a2a-t-negotiation/.../content/NegotiationContentService.java | 内容层共享服务，代理生成编排器 |
| a2a-t-negotiation/.../generation/NegotiationGenerationOrchestrator.java | 内容生成引擎核心 |
| a2a-t-negotiation/.../generation/NegotiationGeneratorRegistry.java | 6 个生成器的 dispatch 表 |
| a2a-t-negotiation/.../generation/DefaultNegotiationContentExtractor.java | LLM 内容抽取（fromText 用） |
| a2a-t-negotiation/.../validation/ParamExtractor.java | 校验+参数提取编排 |
| a2a-t-negotiation/.../validation/DefaultNegotiationComplianceChecker.java | 规则门（确定性） |
| a2a-t-negotiation/.../runtime/NegotiationHandler.java | 状态机 facade |
| a2a-t-negotiation/.../runtime/NegotiationRuntime.java | 状态机核心 |
| a2a-t-negotiation/.../runtime/RoleBoundNegotiationOrchestrator.java | 角色绑定 orchestrator |
| a2a-t-negotiation/.../runtime/helper/NegotiationPayloadMapper.java | payload 序列化/反序列化 |
| a2a-t-negotiation/.../handler/InformationNegotiation.java | information 运行时 handler（唯一有实质逻辑） |
| a2a-t-negotiation/.../content/Vocabulary.java | 多语言词汇表 |

### 引擎侧（workflow-exec-engine-java）

| 文件 | 作用 |
|---|---|
| workflow-engine/.../client/A2ATransport.java | 传输层，持有 A2ATClient |
| workflow-engine/.../client/DefaultWorkflowEngineClient.java | 工作流执行层，协商自动循环 |
| workflow-engine/.../client/NegotiationTHandler.java | 协商扩展 handler（接收侧） |
| workflow-engine/.../client/ExtensionHandler.java | 扩展 handler 接口 |
| workflow-engine/.../client/ExtensionRegistry.java | 扩展 handler 注册表 |
| workflow-engine/.../control/DefaultControlPoint.java | 默认控制点，协商策略委托 |
| workflow-engine/.../control/NegotiationStrategy.java | 协商策略接口 |
| workflow-engine/.../client/WorkflowEngineClientConfig.java | 引擎配置（含 a2atEnvPath） |
| samples/.../agents/NegotiationBaseAgentExecutor.java | server 侧协商基类 |
| samples/.../negotiation/NegotiationUtils.java | 协商常量和工具方法 |
| samples/.../negotiation/NegotiationStrategy.java | 协商策略示例实现 |
