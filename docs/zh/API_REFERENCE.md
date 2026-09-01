# A2A-T 工作流执行引擎 - 接口说明书

## 包总览

| 包                                    | 说明                                  |
|---------------------------------------|---------------------------------------|
| `dev.openan.workflow.engine.client`   | A2A 消息传输、认证、扩展、配置        |
| `dev.openan.workflow.engine.control`  | 用户决策点和事件系统                  |
| `dev.openan.workflow.engine.core`     | DAG 遍历引擎和上下文组装              |
| `dev.openan.workflow.engine.model`    | 数据模型（Workflow、Task、Result 等） |
| `dev.openan.workflow.engine.registry` | PSOP 加载和 AgentCard 注册            |
| `dev.openan.workflow.engine.runner`   | 工作流执行入口                        |
| `dev.openan.workflow.engine.spring`   | Spring Boot 服务端自动配置（starter）      |

---

## dev.openan.workflow.engine.runner

### ExecutePsop

工作流执行入口。使用 Builder 模式。

#### ExecutePsop.Builder

| 方法                                     | 类型 | 默认值      | 说明                            |
|------------------------------------------|------|-------------|---------------------------------|
| `psop(Workflow)`                         | 必填 | -           | PSOP 工作流定义                 |
| `agentCards(List<AgentCard>)`            | 可选 | `List.of()` | 被调度智能体的 AgentCard；存在远程步骤且未传入已配置 `engineClient` 时必须提供 |
| `engineClient(WorkflowEngineClient)`     | 可选 | null        | 预配置客户端（null=自动创建）   |
| `controlPoint(ControlPoint)`             | 必填 | -           | 用户决策实现                    |
| `runtimeIntent(String)`                  | 可选 | `""`        | 自然语言意图，用于上下文组装    |
| `lang(String)`                           | 可选 | `"zh"`      | 语言提示（`"zh"` 或 `"en"`）    |
| `credentialsConfigPath(String)`          | 可选 | null        | 凭证 JSON 文件路径              |
| `sslVerify(boolean)`                     | 可选 | `true`      | 是否验证 TLS 证书               |
| `caCertsPath(String)`                    | 可选 | null        | CA 证书 PEM 文件路径            |
| `a2aClientRuntime(A2AJavaClientRuntime)` | 可选 | null        | 自定义运行时（null = 自动创建） |
| `eventCallback(EventCallback)`           | 可选 | null        | 实时事件回调                    |
| `onFinish(BiConsumer)`                   | 可选 | null        | 执行完成回调                    |
| `onEvent(Function)`                      | 可选 | null        | 单事件转换钩子                  |

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(cards)
        .controlPoint(cp)
        .runtimeIntent("分析请求")
        .sslVerify(false)
        .execute()
        .get(10, TimeUnit.MINUTES);
```

**返回：** `CompletableFuture<ExecutionResult>`

---

## dev.openan.workflow.engine.client

### WorkflowEngineClient

```java
CompletableFuture<SendMessageResult> dispatch(TaskRequest request, MessageContent content, ControlPoint callbacks);
CompletableFuture<SendMessageResult> sendMessage(String agentName, MessageContent content);
CompletableFuture<SendMessageResult> getTask(String agentName, String taskId);
CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId);
CompletableFuture<SendMessageResult> subscribeToTask(String agentName, String taskId, Consumer<Map<String,Object>> callback);
long callbackTimeoutSeconds();
void setControlPoint(ControlPoint callbacks);
void setEventCallback(EventCallback callback);
void close();
```

执行器内部调用 dispatch；onTask 不自行发送。内容是最终 parts/metadata/extensions，引擎只管理信封和交互，不创建 A2ATClient，不按
AgentCard 声明生成内容。模板查询和生成接口请直接使用宿主 SDK。

只有远端 `INPUT_REQUIRED` 携带有效 Negotiation-T Propose 才进入 `onNegotiation`。 终态不会重启协商，普通 INPUT_REQUIRED
明确报告不支持的交互。 宿主自行校验、理解 Propose，并用自己的 A2A-T client 生成最终 Accept/Reject/Abort。 通过
`A2atMessages.contextOf(request.received())` 取得收到的上下文； 结束回复保持相同 id、round、maxRounds，最后允许的一轮仍可回答，不自行
nextRound 或返回新 Propose。

返回 `new NegotiationReply.Send(content)` 发送最终内容； 返回 `new NegotiationReply.Stop(code, reason)` 只在本地停止，不生成
Abort。 同一任务／会话／轮次的重复等待事件不会重复回调、重复提交；未变化状态通过 getTask 观察。
`maxNegotiationExchanges` 默认 3，是独立于 SDK context.maxRounds 的本地交互资源预算。 超时、预算耗尽、回调缺失均明确失败，不默认
Accept，也不自动生成 Abort。 Accept/Reject 的 SUBMITTED/WORKING ACK 仍需等待任务结果，不重发原命令。 业务发送 Abort 后，即使远端用
COMPLETED 确认，也不能判为任务成功。

### ExtensionSender

```java
CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);
NotificationSubscription openNotification(String agentName, MessageContent content,
    BiConsumer<NotificationSubscription, ReceivedMessage> listener);
```

授权和订阅接收宿主生成的最终内容。三类操作使用独立 transport/runtime/context，成功与否不阻断工作流。openNotification 先注册
handle 再开始 I/O，监听器直接收到 handle 和完整 ReceivedMessage。acknowledgement() 是实际 ACK；超时失败。close()
请求关闭，completion() 在流真正退出后完成。

### WorkflowEngineClientConfig

工作流引擎客户端的 Builder 配置。

| 属性                            | 类型           | 默认值 | 说明                                                                                                      |
|---------------------------------|----------------|--------|-----------------------------------------------------------------------------------------------------------|
| `sslVerify`                     | `boolean`      | `true` | HTTP/JSON-RPC 的 TLS 证书链验证；关闭时仍校验主机名并保留 mTLS 客户端身份。默认 gRPC 关闭时使用 plaintext |
| `caCertsPath`                   | `String`       | null   | CA 证书 PEM 文件路径                                                                                      |
| `clientCertPath`                | `String`       | null   | mTLS 客户端证书链路径；默认 gRPC 需配合 `sslVerify=true`                                                  |
| `clientKeyPath`                 | `String`       | null   | mTLS PKCS#8 PEM/DER 私钥路径                                                                              |
| `clientKeyPassword`             | `String`       | null   | 加密 PKCS#8 私钥密码                                                                                      |
| `crlPath`                       | `String`       | null   | HTTP/JSON-RPC 的 X.509 CRL 路径；默认 gRPC runtime 暂不支持并会拒绝启动                                   |
| `sendTimeoutSeconds`            | `long`         | `600`  | SSE 流超时（默认 10 分钟）                                                                                |
| `notificationAckTimeoutSeconds` | `long`         | `5`    | Notification-T 首个 ACK/事件等待时间                                                                      |
| `sendExecutorCoreSize`          | `int`          | `4`    | 发送线程池核心线程数                                                                                      |
| `sendExecutorMaxSize`           | `int`          | `16`   | 发送线程池最大线程数                                                                                      |
| `sendExecutorQueueCapacity`     | `int`          | `256`  | 发送线程池有界队列容量                                                                                    |
| `authProvider`                  | `AuthProvider` | null   | 自定义认证提供器                                                                                          |
| `credentialsConfigPath`         | `String`       | null   | 凭证 JSON 文件路径；显式配置缺失或损坏时启动失败                                                          |
| `credentialEncryptionKey`       | `String`       | null   | 宿主显式传入凭据解密密钥，不从 LLM .env 加载                                                              |
| `credentialsConfig`             | `Map`          | null   | 内联凭证配置；AgentCard 声明安全要求时必须匹配                                                            |
| `maxNegotiationExchanges`       | `int`          | `3`    | 本地交互预算，独立于 SDK maxRounds；耗尽仅本地失败                                                        |

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("ca.pem")
        .clientCertPath("client-cert.pem")
        .clientKeyPath("client-key.pem")
        .sendTimeoutSeconds(900)
        .credentialsConfigPath("creds.json")
        .maxNegotiationExchanges(5)
        .authProvider(myProvider)
        .build();
```

### AuthProvider

非标准认证机制的自定义认证提供器。

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard,
                   Map<String, String> headers);
}
```

每次消息发送时调用。`headers` 是可变 Map；直接添加 `Authorization`、自定义头等。`AuthProvider` 可作为唯一认证来源，包括
AgentCard 的 `securityRequirements` 非空但未配置 credentials 的场景。若同时配置 credentials，两者分别计算后合并；若同名
Header 生成不同值，引擎会抛出 `SecurityException`，不会静默覆盖。

### A2atMessages

通过 MessageContent(parts, metadata, extensions) 提交自定义扩展内容，不注册引擎内容处理器。A2atMessages.from
(MetadataContent, List<Part<?>>) 保留 SDK metadata 原位置并激活对应 URI；contextOf(ReceivedMessage) 或 contextOf(Map<
String,Object>) 读取并检查规范协商上下文。只有 a2a-t-core 依赖，没有内容生成或语义校验。

### A2AJavaClientRuntime

A2A SDK 消息传输运行时接口。实现此类可自定义 HTTP 传输行为。

```java
public interface A2AJavaClientRuntime {
    Iterable<ClientEvent> sendMessage(
            AgentCard agentCard, MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink);

    void close();
}
```

引擎提供默认实现。仅在需要自定义 HTTP 传输时实现此接口。

### ConversationScopedA2AJavaClientRuntime

可选的生命周期回调接口，适用于传输会话跨越多个 A2A 请求的运行时实现（例如网关登录需在所有协商轮次期间保持存活）。

```java
public interface ConversationScopedA2AJavaClientRuntime {
    void closeConversation(AgentCard agentCard, String contextId);
}
```

当运行时同时实现此接口时，引擎会在完整的发送 + 协商周期完成后调用 `closeConversation`——而不是每次 HTTP
请求之后。这样网关会话只会在逻辑会话结束后才释放。

与 `A2AJavaClientRuntime` 一起实现：

```java
public class MyGatewayRuntime
        implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {
    @Override
    public Iterable<ClientEvent> sendMessage(...) { ... }

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
        // 释放网关会话
    }

    @Override
    public void close() { ... }
}
```

### AgentCardJacksonModule

用于反序列化 AgentCard JSON 的 Jackson 模块，包含安全方案归一化。处理 A2A SDK 强类型 `AgentCard` record 所需的 OpenAPI 格式
`securitySchemes` / `securityRequirements` 字段。

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);
```

### AgentCardNormalizer

将原始 `Map<String, Object>`（注册中心 API 返回）归一化为 `AgentCard` 兼容格式的工具类。`RegistryClient.fetchAgentCards()`
内部使用。也作为公共静态方法供自定义归一化使用：

```java
Map<String, Object> normalized = AgentCardNormalizer.normalize(rawMap);
```

### 其他公开 client 类型

以下公开类型面向高级集成。除非需要自定义 runtime、观测适配或显式生命周期管理，优先使用上述高层接口。

| 类型                          | 用途 |
|-------------------------------|------|
| `A2ATExtension`               | 规范扩展名称与 URI |
| `A2ATransport`                | 底层传输、认证、响应组装与订阅生命周期 |
| `DefaultWorkflowEngineClient` | `WorkflowEngineClient` 默认实现 |
| `DefaultExtensionSender`      | 基于 `A2ATransport` 的 `ExtensionSender` 默认实现 |
| `DefaultA2AJavaClientRuntime`  | HTTP/JSON-RPC/gRPC 的默认 A2A Java SDK runtime |
| `CredentialCrypto`            | AES-GCM 凭据加密工具与命令行入口 |
| `EnvFileLoader`               | 显式加载宿主自有 `.env` 配置 |
| `SslContextFactory`           | 传输与发现辅助 API 的 TLS 上下文构造 |
| `ProtocolResponses`           | A2A 事件与结果组装辅助方法 |
| `ClientEventMapper`           | 面向回调和诊断的稳定事件投影 |
| `WireLog`                     | 关联上下文与协议观测门面 |
| `RemoteProblemException`      | 结构化远程问题响应（`status`、`title`、`detail`、`type`、`timestamp`） |

---

## dev.openan.workflow.engine.control

### ControlPoint

```java
interface ControlPoint {
    CompletableFuture<MessageContent> onTask(TaskRequest request);
    CompletableFuture<TaskResult> onSelfTask(TaskRequest request);
    CompletableFuture<RouteDecision> onRoute(RouteRequest request);
    CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request);
}
```

onTask 返回最终 parts/metadata/extensions，引擎封装发送，不再生成或改写内容。 onSelfTask 返回本地 TaskResult；onRoute
选择允许的候选；onNegotiation 返回 Send 或 Stop。 未实现的回调明确失败，不回显成功、不选首分支、不自动同意。

`DefaultControlPoint` 保留上述快速失败默认值，并可把 `onNegotiation` 委托给注入的
`NegotiationStrategy`。仅需定制协商策略时实现 `NegotiationStrategy.resolve(NegotiationRequest)`；同时需要定制任务、宿主本地任务或路由时，实现或构建完整 `ControlPoint`。
字段与完整示例见 [业务回调集成契约](BUSINESS_CALLBACKS.md)。

### EventCallback

```java
public class EventCallback {
    public void onEvent(String eventType, Map<String, Object> data) {
    }
}
```

重写此方法以接收实时执行事件。事件类型定义在 `EventType` 常量中。

### EventType

| 常量                     | 说明                                               |
|--------------------------|----------------------------------------------------|
| `STEP_START`             | 工作流步骤开始                                     |
| `STEP_COMPLETE`          | 工作流步骤完成                                     |
| `TASK_REQUEST`           | 任务分派给智能体                                   |
| `TASK_RESPONSE`          | 收到任务响应                                       |
| `TASK_STATUS_CHANGED`    | 任务状态变更（pending → running → success/failed） |
| `AGENT_REQUEST`          | 消息发送给智能体                                   |
| `AGENT_RESPONSE`         | 收到智能体响应                                     |
| `AGENT_STATUS_UPDATE`    | 智能体 SSE 状态更新                                |
| `AGENT_ARTIFACT_UPDATE`  | 智能体 SSE artifact 更新                           |
| `AGENT_MESSAGE_EVENT`    | 智能体 SSE 消息事件                                |
| `NEGOTIATION_REQUEST`    | 智能体请求协商                                     |
| `NEGOTIATION_RESOLVED`   | 补充信息已发送                                     |
| `NEGOTIATION_FAILED`     | 协商无法解决                                       |
| `AUTHORIZATION_REQUEST`  | 智能体请求授权                                     |
| `AUTHORIZATION_RESOLVED` | 授权决策已做出                                     |
| `NOTIFICATION`           | 收到智能体通知                                     |
| `ROUTE_DECISION`         | 路由决策已做出                                     |
| `WORKFLOW_COMPLETE`      | DAG 调度结束，需检查 success；不代表全部节点成功执行 |
| `START`                  | 工作流执行开始                                     |
| `COMPLETE`               | 工作流执行成功完成                                 |
| `ERROR`                  | 工作流执行失败                                     |
| `CLOSE`                  | 引擎客户端已关闭                                   |

---

## dev.openan.workflow.engine.registry

### LoadPsop

从编排中心加载和搜索 PSOP 工作流。

#### load

```java
static Workflow load(String baseUrl, String psopId,
                     String accessToken, boolean sslVerify)

static Workflow load(String baseUrl, String psopId)
```

GET `/api/v1/orchestrate/psop/{psop_id}`。返回完整工作流（含步骤、子任务、路由条件）。

#### search

```java
static List<WorkflowSearchResult> search(
        String baseUrl, String intent, int topN,
        String accessToken, boolean sslVerify)

static List<WorkflowSearchResult> search(
        String baseUrl, String intent)
```

POST `/api/v1/orchestrate/search`。返回按自然语言意图匹配的工作流摘要列表。

LoadPsop 的简便重载默认 `sslVerify=true`，使用 JVM 信任库并校验主机名。
显式传 `false` 时，仅该编排中心 HTTPS 连接跳过证书链和主机名校验，可免配本地 CA 文件联调。
服务端仍须提供 HTTPS 证书；此开关不绕过 mTLS，也不修改其他客户端或 JVM 的全局 TLS 策略。
生产环境必须保持验证并配置正确的服务端 SAN 与信任库；本设置不是引擎南向 HTTP/JSON-RPC 的 TLS 策略变更。

### RegistryClient

双参数构造默认使用 30 秒完整响应截止时间。可通过
`new RegistryClient(url, sslVerify, Duration.ofSeconds(15))` 设置正值预算，包含响应正文读取；线程中断会取消待处理请求。
注册中心方法返回 JSON Map，使用 AgentCardJacksonModule 转换为 AgentCard，详见集成指南。


从注册中心获取和注册 AgentCard。

```java
new RegistryClient("https://127.0.0.1:5000",false)

List<Map<String, Object>> fetchAgentCards()

Map<String, Object> fetchAgentCard(String name)

Map<String, Object> fetchAgentCard(String name, String organization)

Map<String, Object> registerAgentCard(Map<String, Object> agentCard)
```

- `fetchAgentCards`：获取所有 AgentCard
- `fetchAgentCard`：按名称（可选按组织）获取单个 AgentCard
- `registerAgentCard`：注册 AgentCard

---

## dev.openan.workflow.engine.core

### WorkflowExecutor

中层 DAG 遍历引擎。遍历工作流步骤，通过 `ContextBuilder` 选择强类型上游执行结果，并行分派子任务，应用步骤成功策略（
`ALL_SUCCESS` / `ANY_SUCCESS` / `SELF_LOOP`），并路由到下一步。

SDK 用户通常不直接实例化——`ExecutePsop` 内部封装了它。供需要在不含 runner 生命周期管理的情况下运行遍历层的高级集成使用。

```java
WorkflowExecutor executor = new WorkflowExecutor(
        workflow, controlPoint, engineClient,
        eventCallback, runtimeIntent, lang);
ExecutionResult result = executor.run().join();
```

---

## dev.openan.workflow.engine.model

### Workflow

| 字段          | 类型                 | 说明           |
|---------------|----------------------|----------------|
| `id`          | `String`             | 工作流 ID      |
| `name`        | `String`             | 工作流名称     |
| `description` | `String`             | 描述           |
| `steps`       | `List<WorkflowStep>` | 有序工作流步骤 |

静态方法：`Workflow.fromMap(Map<String, Object>)` 从编排中心 API 响应解析。

### WorkflowStep

| 字段          | 类型                  | 默认值        | 说明                                                                       |
|---------------|-----------------------|---------------|----------------------------------------------------------------------------|
| `name`        | `String`              | -             | 步骤名（工作流内唯一）                                                     |
| `subtasks`    | `List<Task>`          | `List.of()`   | 此步骤分派的子任务                                                         |
| `next`        | `List<JumpCondition>` | `List.of()`   | 条件后续步骤                                                               |
| `layer`       | `int`                 | `0`           | 上下文层（0 = 仅运行时意图）                                               |
| `contextFrom` | `List<String>`        | null          | 聚合来源；省略 = 直接前驱，`[]` = 不聚合，`"*"` = 所有祖先，或指定祖先名称 |
| `stepType`    | `StepType`            | `ALL_SUCCESS` | 执行模式                                                                   |

### StepType

| 值            | 说明                                                                                    |
|---------------|-----------------------------------------------------------------------------------------|
| `ALL_SUCCESS` | 所有子任务必须成功                                                                      |
| `ANY_SUCCESS` | 任一子任务成功即可                                                                      |
| `SELF_LOOP`   | 宿主智能体通过 `onSelfTask` 本地处理，不发 A2A-T 消息。成功语义同 `ALL_SUCCESS`。       |

### TaskStatus

任务生命周期状态，用于 `TASK_STATUS_CHANGED` 事件，与 Python SDK 保持跨 SDK 一致性。

| 值        | 字符串      | 说明                 |
|-----------|-------------|----------------------|
| `PENDING` | `"pending"` | 任务已创建，尚未开始 |
| `RUNNING` | `"running"` | 任务进行中           |
| `SUCCESS` | `"success"` | 任务成功完成         |
| `FAILED`  | `"failed"`  | 任务失败             |

### Task

| 字段          | 类型     | 说明                              |
|---------------|----------|-----------------------------------|
| `agent`       | `String` | 智能体名称（匹配 AgentCard.name） |
| `skill`       | `String` | 智能体技能 ID                     |
| `description` | `String` | 任务描述                          |

### JumpCondition

| 字段        | 类型     | 说明                                   |
|-------------|----------|----------------------------------------|
| `step`      | `String` | 下一步名称（`"end"` 表示终止）         |
| `condition` | `String` | 条件表达式（`"success"`、`"fail"` 等） |

### TaskRequest / BusinessInput

TaskRequest 使用 getXxx() 访问器：

| 字段                         | 含义                                                             |
|------------------------------|------------------------------------------------------------------|
| executionId / taskId         | 本地执行／逻辑任务标识，不是远端协议 ID                          |
| stepName / agentName / skill | 当前步骤、目标智能体和技能                                       |
| instruction / language       | 当前指令，不含引擎拼接的历史                                     |
| input                        | BusinessInput：文本或任意 JSON 可序列化数据，二选一，不含 schema |
| workflowInput                | WorkflowInput(runtimeIntent, upstreamResults)，与当前输入分离    |

BusinessInput.text(value) / BusinessInput.data(value) 创建输入快照。
WorkflowInput、UpstreamStepResult、ReceivedMessage、NegotiationRequest 等 record 使用 field() 访问器。
`BusinessValues.snapshot`、`map` 和 `list` 为宿主自有数据提供相同的防御性 JSON 快照；嵌套数组仍是独立值，返回集合不可修改。

| contextFrom   | 上游选择                         |
|---------------|----------------------------------|
| 未指定 / null | 已产生结果的直接前驱             |
| []            | 不聚合上游，runtimeIntent 仍保留 |
| ["*"]         | 已产生结果的全部祖先             |
| 显式祖先名称  | 按声明顺序选取对应结果           |

contextFrom 只选择证据，不建立执行依赖；依赖由 next 定义。 未知或非祖先名称、通配符与名称混用均非法；未激活分支不虚构结果。
引擎不把窗口附加到 instruction/parts，也不调用 LLM 映射上游；由宿主决定怎么消费或映射下游输入。

窗口结构：stepName → taskResults[] → outputs[] / receivedMessages[]。 TaskExecutionResult 还保留 agentName、skill、逻辑
taskId、taskDescription、status、 error、errorCode、errorDetails。多子任务不混合，嵌套数组仍作为一个输出项。 输出不要求来自
LLM，也不强制符合特定领域模板。

### MessageContent / ReceivedMessage

```java
record MessageContent(List<Part<?>> parts, Map<String,Object> metadata, Set<String> extensions) {}
record ReceivedMessage(MessageContent message, Map<String,Object> taskMetadata, List<Artifact> artifacts) {}
```

通过 MessageContent.text(text)、MessageContent.parts(parts) 或构造器创建快照。 TextPart、DataPart、FilePart 保留顺序及各自
metadata，文件引用不会自动下载。 MessageContent 不提供 role、目标、messageId、taskId、contextId 或认证头。 业务 metadata 中即使存在
contextId 字段，也不能覆盖真实 A2A 信封。

ReceivedMessage 分别保留消息 metadata、任务 metadata、artifact 身份及 parts/metadata，不互相覆盖。 message 可以为空；只有
metadata 的业务结果仍可从完整视图读取。 便利 outputs 按 artifact 与 part 顺序提取 TextPart 文本和 DataPart 数据； 没有
artifact 时，可提取成功终态或独立 Message 的正文。 FilePart 仅在完整视图提供。不解析文本、不拼接相邻文本、不拍平嵌套业务数组。
失败状态消息只保留作证据，不进入 outputs；已返回的有效部分 artifact 仍保留。 SSE append/replace 按 artifact 组装，最终快照不重复累加。

本地 onSelfTask 返回 TaskResult.success(List<Object>)，允许空列表； TaskResult.failure(code, message) 将错误与输出分开，builder
可保留有效部分输出。 远端 TaskResult 的 receivedMessages 是完整证据，便利输出由它派生。 远端 Task 只有 COMPLETED 才成功，独立
A2A Message 也可完成交互。 进度／协商提示不会因为含文本就变成工作流成功。

### NegotiationRequest / NegotiationReply

参见 [BUSINESS_CALLBACKS](BUSINESS_CALLBACKS.md).

### SendMessageResult

getReceivedMessages() 是保留层级的响应来源，getOutputs() 为便利投影；getTask()/getTaskState()
保留实际远端状态，failureCode/failureMessage 为独立本地交互失败。text 和扁平 metadata 仅用于传输诊断，不用它们代替完整业务响应。

### ExecutionResult

| 字段          | 类型               | 说明               |
|---------------|--------------------|--------------------|
| `success`     | `boolean`          | 工作流是否成功     |
| `history`     | `List<Map>`        | 每步执行历史       |
| `stepOutputs` | `Map<String, Map>` | 按步骤名索引的输出 |
| `error`       | `String`           | 错误信息（失败时） |

### RouteDecision

| 字段       | 类型     | 说明       |
|------------|----------|------------|
| `nextStep` | `String` | 下一步执行 |
| `reason`   | `String` | 决策原因   |

### WorkflowSearchResult

| 字段             | 类型           | 说明         |
|------------------|----------------|--------------|
| `workflowId`     | `String`       | 工作流 ID    |
| `workflowType`   | `String`       | 类型         |
| `name`           | `String`       | 名称         |
| `description`    | `String`       | 描述         |
| `tags`           | `List<String>` | 标签         |
| `createdAt`      | `String`       | 创建时间     |
| `score`          | `double`       | 相关度评分   |
| `userIntent`     | `String`       | 匹配的意图   |
| `relatedPreflow` | `String`       | 关联 preflow |
| `tasksSummary`   | `String`       | 任务摘要     |

---

## 扩展 URI 常量

| 扩展            | URI                                                                                       |
|-----------------|-------------------------------------------------------------------------------------------|
| Task-T          | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1`          |
| Negotiation-T   | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1`   |
| Authorization-T | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1` |
| Notification-T  | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1`  |

规范协商 metadata 使用 `templateUri` 和 `negotiationContext`。后者携带
`{id, round, maxRounds, performative}`，引擎解析协商消息时必须存在。引擎不会向线上报文添加私有协商状态键。

---

## 线程安全

- 引擎客户端线程安全，内部使用并发集合。
- `ControlPoint` 实现若在多工作流并发执行中使用，需自行保证线程安全。
- `EventCallback.onEvent` 从多个线程调用（主线程 + SSE 工作线程），需要时使用同步。

## 错误处理

- 顶层远端 problem 错误通过 `RemoteProblemException` 保留；可用 `findIn(Throwable)` 查找包装异常中的原因。
  工作流任务失败信息为 `remote.problem.<status>`，详情进入 history / TASK_RESPONSE，不作为业务 outputs。
  引擎节点事件携带 executionId，任务结果事件和 history 携带逻辑 taskId。
  失败传播、并行步骤失败规则和日志职责见 [集成指南](INTEGRATION_GUIDE.md#14-远端错误响应)。

- 回调异常／空值／超时明确失败；SDK 内容错误由宿主转换为 BusinessFailure，必要时保留安全的 code/details。
- maxNegotiationExchanges 耗尽或本地 Stop 只结束本地交互，不自动发送 Abort。业务 Send(Abort) 不算任务成功。
- 远端状态和错误与业务输出分开，失败状态消息不成为 outputs，已有部分 artifact 保留。
- 缺失必需认证信息时拒绝发送，不静默匿名访问。
- SDK 流退出／传输错误日志是传输观察，不等同于工作流最终状态；以 ExecutionResult 和远端任务状态判断结果。

---

## spring-boot-starter 模块

`spring-boot-starter` 模块为 A2A **服务端**（非客户端/工作流侧）提供 Spring Boot 自动配置。当位于 Spring Boot Web 应用的
classpath 时，自动将所有 A2A SDK 服务端组件注册为 Spring Bean。

### A2AProperties

以 `a2at.server` 为前缀的配置属性：

| 属性                                         | 默认值                     | 说明                                                     |
|----------------------------------------------|----------------------------|----------------------------------------------------------|
| `a2at.server.agent-card`                     | `classpath:agentcard.json` | AgentCard JSON 文件路径（支持 classpath: 或 file: 前缀） |
| `a2at.server.path-prefix`                    | `/a2a/json`                | A2A 端点的 URL 路径前缀                                  |
| `a2at.server.agent-timeout-seconds`          | `30`                       | Agent 执行超时（秒）                                     |
| `a2at.server.consumption-timeout-seconds`    | `5`                        | 消费超时（秒）                                           |
| `a2at.server.reconciliation-timeout-seconds` | `1`                        | 协调等待超时（秒）                                       |
| `a2at.server.executor-core-size`             | `8`                        | 服务端执行器核心线程数                                   |
| `a2at.server.executor-max-size`              | `8`                        | 服务端执行器最大线程数                                   |
| `a2at.server.executor-queue-capacity`        | `100`                      | 服务端执行器有界队列容量                                 |
| `a2at.server.executor-keep-alive-seconds`    | `60`                       | 非核心线程存活时间（秒）                                 |

```yaml
a2at:
  server:
    agent-card: classpath:agentcard/my_agent.json
    path-prefix: /a2a/json
    agent-timeout-seconds: 30
    executor-core-size: 8
    executor-max-size: 16
    executor-queue-capacity: 200
```

### A2AAutoConfiguration

自动配置以下 Bean（均为 `@ConditionalOnMissingBean`，可覆盖任意一个）：

| Bean                | 类型                          | 用途                                                   |
|---------------------|-------------------------------|--------------------------------------------------------|
| `agentCard`         | `AgentCard`                   | 通过 Jackson 从 `a2at.server.agent-card` 路径加载      |
| `a2aConfigProvider` | `A2AConfigProvider`           | SDK 配置值                                             |
| `taskStore`         | `InMemoryTaskStore`           | 内存任务存储                                           |
| `eventBus`          | `MainEventBus`                | 用于 SSE 流的事件总线                                  |
| `queueManager`      | `InMemoryQueueManager`        | 事件队列管理器                                         |
| `pushStore`         | `PushNotificationConfigStore` | 推送通知配置存储                                       |
| `agentExecutorPool` | `ExecutorService`             | 智能体执行线程池（8 线程，守护线程）                   |
| `eventBusProcessor` | `MainEventBusProcessor`       | 事件总线处理器                                         |
| `requestHandler`    | `RequestHandler`              | 默认请求处理器                                         |
| `restHandler`       | `RestHandler`                 | REST 协议处理器                                        |
| `a2aController`     | `A2AController`               | Spring MVC 控制器（`message:send` + `message:stream`） |

### A2AController

暴露 A2A REST 端点的 Spring MVC 控制器：

- `POST {path-prefix}/message:send` — 阻塞式发送
- `POST {path-prefix}/message:stream` — SSE 流式

### 用法

合作方只需提供 `AgentExecutor` 实现：

```java
@Component
public class MyAgentExecutor implements AgentExecutor {
    @Override
    public ExecuteResult execute(ExecuteRequest request) {
        // 业务逻辑
        return ExecuteResult.builder()
                .addTextPart("result text")
                .build();
    }
}
```

其余 Bean（AgentCard、RequestHandler、RestHandler、A2AController 等）均自动配置。
