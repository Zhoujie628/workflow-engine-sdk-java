# A2A-T 工作流执行引擎 - 二次开发集成指南

## 1. 概述

A2A-T 工作流执行引擎是一个 Java SDK，用于基于 A2A 协议和 A2A-T 电信扩展编排多智能体工作流。

引擎自动处理 A2A 协议层的全部机制（消息收发、SSE 流式传输、Task-T 提示词生成、Negotiation-T 协商循环、认证、TLS），你只需关注业务决策。

## 2. 环境要求

| 要求  | 版本 |
|-------|------|
| JDK   | 17+  |
| Maven | 3.6+ |

## 3. 引入依赖

```xml

<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
<version>1.0.0</version>
</dependency>
```

## 4. 快速上手

整个集成过程分四步：定义工作流 -> 加载 AgentCard -> 实现 ControlPoint -> 执行。

### 4.1 定义工作流

```java
Workflow workflow = Workflow.builder()
        .name("故障诊断")
        .steps(List.of(
                WorkflowStep.builder()
                        .name("diagnose")
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("SPN Domain Agent")
                                        .skill("diagnosis")
                                        .description("诊断故障")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("merge")
                                        .condition("success")
                                        .build()))
                        .layer(0)
                        .build(),
                WorkflowStep.builder()
                        .name("merge")
                        .stepType(StepType.SELF_LOOP)   // 自环节点：工作台本地汇总，不发 A2A-T 给自己
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Transport Workbench Agent")
                                        .skill("aggregate")
                                        .description("汇总结果")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("end")
                                        .condition("success")
                                        .build()))
                        .layer(1)
                        .contextFrom(List.of("*"))
                        .build()
        ))
        .build();
```

### 4.2 加载 AgentCard

```java
// 方式一：从 JSON 文件加载
ObjectMapper mapper = new ObjectMapper()
                .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);

// 方式二：从注册中心拉取
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
List<Map<String, Object>> cards = registry.fetchAgentCards();
```

### 4.3 实现 ControlPoint

继承 `DefaultControlPoint`，按需覆盖以下方法：

```java
public class MyControlPoint extends DefaultControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, TaskDispatcher dispatcher) {
        return dispatcher.dispatch(TaskSubmission.fromText(
                        request.getAgentName(), request.getMessage(),
                        StandardTemplates.PRIVATE_LINE_COMPLAINT))
                .thenApply(r -> {
                    String state = r.getTaskState();
                    boolean success = state == null || state.isBlank()
                            ? r.getText() != null && !r.getText().isBlank()
                            : state.endsWith("COMPLETED");
                    return TaskResponse.builder()
                            .success(success)
                            .output(r.getText())
                            .build();
                });
    }

    @Override
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        // SELF_LOOP 步骤在这里本地处理，不需要 engineClient，不发 A2A-T 消息。
        // request.getMessage() 已包含上游步骤的执行结果上下文。
        String summary = summarizeLocally(request.getMessage());
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(summary).build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .build());
    }

    @Override
    public CompletableFuture<NegotiationDecision> onNegotiation(
            NegotiationRequest request) {
        return CompletableFuture.completedFuture(
                NegotiationDecision.acceptText("请使用现有信息继续执行。"));
    }
}
```

| 方法              | 何时调用                       | 你需要做什么                                             |
|-------------------|--------------------------------|----------------------------------------------------------|
| `onTask`          | 步骤向其他智能体分派任务时     | 向 `TaskDispatcher` 提交强类型 `TaskSubmission`         |
| `onSelfTask`      | `SELF_LOOP` 步骤本地执行时     | 本地处理并返回结果（不发 A2A-T 消息）                    |
| `onRoute`         | 步骤完成后、决定下一步前       | 从候选分支中选择下一步                                   |
| `onNegotiation`   | 智能体返回 `INPUT_REQUIRED` 时 | 返回强类型 `NegotiationDecision`                         |

`onNegotiation` 默认返回通用的 `acceptText` 决策。结构化业务数据应使用
`acceptData/rejectData/abortData`，不要拼接字符串控制前缀。信息协商使用 `rejectData`
时，Map 必须以每个无法提供的信息项名称为 key、具体原因为 value 逐项填写；不能只传一个汇总的
“拒绝原因”。例如同时无法提供端口和投诉分类时，应分别传入 `接入端口名称` 与 `投诉分类` 两项。

**流程外操作（Authorization-T / Notification-T）**：两者都由宿主在各自业务时机通过
`ExtensionSender` 独立触发，与工作流没有固定先后关系。Authorization-T 是响应完成即结束的一次性请求；
Notification-T 是独立于单次工作流 transport 的长连接订阅，ACK 通过
`NotificationSubscription.acknowledgement()` 获取，后续事件通过回调持续接收，直到收到目标结果、
显式取消或宿主关闭订阅。

**自环节点（SelfLoop）**：当一个步骤是工作流执行智能体自身的任务（例如汇总多个智能体的诊断结果），把 `stepType` 设为
`SELF_LOOP`。引擎会调用 `onSelfTask` 本地处理，而不是通过 A2A-T 协议给智能体自己发消息。`onSelfTask` 不接收 `engineClient`
参数——从契约上保证自环任务不会误发 A2A-T。只有发给其他智能体的步骤才走 `onTask` + A2A-T 协议。

### 4.4 执行

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(List.of(card1, card2))
        .controlPoint(new MyControlPoint())
        .runtimeIntent("SPN跨城专线故障诊断与抢通")
        .lang("zh")
        .a2atEnvPath(".env")
        .credentialsConfigPath("credentials.json")
        .sslVerify(true)
        .onFinish((r, history) -> {
            System.out.println("执行结果: " + r.isSuccess());
        })
        .execute()
        .get(10, TimeUnit.MINUTES);
```

必填项：`psop`、`controlPoint`。其余配置项都有默认值。

## 5. 配置

### 5.1 .env 文件

配置 LLM 和提示词运行时：

```ini
A2AT_LANGUAGE=zh-CN
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LLM_MAX_TOKENS=2000
A2AT_LLM_TEMPERATURE=0
A2AT_LLM_TIMEOUT_SECONDS=60
A2AT_CRED_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

不配置 `.env` 时，Task-T 提示词生成不可用，其余功能不受影响。

### 5.2 凭证配置文件

需要认证的智能体，提供 JSON 凭证文件：

```json
{
  "SPN Domain Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:26335/rest/plat/smapp/v1/oauth/token",
      "method": "PUT",
      "request_fields": {
        "grantType": "password",
        "userName": "admin",
        "value": "enc:<base64-iv>:<base64-ciphertext>",
        "ipaddr": "*"
      },
      "token_field": "accessSession",
      "token_ttl": 3600
    }
  }
}
```

- 加密密码使用 `enc:<iv>:<ciphertext>` 格式，密钥来自 `A2AT_CRED_KEY`
- 也接受明文密码（不加 `enc:` 前缀）
- Token 自动缓存和刷新

### 5.2.1 凭证加密与密钥管理

凭证文件中的密码支持加密存储，避免明文泄露。

**生成密钥**

```bash
openssl rand -hex 32
```

输出示例：

```
4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

将生成的密钥写入 `.env` 文件：

```
A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

**加密密码**

```bash
# 方式一：先设置环境变量
set A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123"

# 方式二：密钥作为第二个参数
java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123" 4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

输出：

```
enc:uHQcTeKZMVNRM9Ga:o5vm4weRozBXBs04phrLq7j7+/yRVyDsrw==
```

将输出结果填入凭证 JSON 的 `value` 字段。

**更换密钥**

1. 生成新密钥：`openssl rand -hex 32`
2. 更新 `.env` 中的 `A2AT_CRED_KEY`
3. 用新密钥重新加密所有密码：`java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "明文密码" 新密钥`
4. 将新的 `enc:...` 结果更新到凭证 JSON 文件

> `.env` 文件不应提交到版本库，建议加入 `.gitignore`。
### 5.3 自定义认证（AuthProvider）

当令牌由工作台或外部认证服务获取，或使用非标准认证方式时，实现 `AuthProvider` 接口。接口只有一个方法：

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers);
}
```

每次发消息前都会调用 `applyAuth`，实现方往 `headers` 里塞认证头即可。

**场景 1：企业 SSO / 外部 Token 服务**

```java
public class SsoAuthProvider implements AuthProvider {
    private final SsoClient ssoClient;

    public SsoAuthProvider(SsoClient ssoClient) {
        this.ssoClient = ssoClient;
    }

    @Override
    public void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers) {
        String token = ssoClient.getToken(agentName);
        headers.put("Authorization", "Bearer " + token);
    }
}

// 注册
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider(new SsoAuthProvider(mySsoClient))
        .sslVerify(true)
        .a2atEnvPath(".env")
        .build();
```

**场景 2：AgentCard 没声明 securitySchemes，但服务端要求认证**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            headers.put("X-API-Key", "static-api-key-value");
        })
        .build();
```

**场景 3：自定义 Header 名称（非标准 Authorization）**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            String token = refreshTokenIfNeeded(agentName);
            headers.put("X-Auth-Token", token);
            headers.put("X-Tenant-Id", "tenant-001");
        })
        .build();
```

**注意事项：**

- `applyAuth` 每次发消息都会调用，内部可自行实现 token 缓存和刷新逻辑
- `securitySchemes` 表示智能体支持的认证方式；`securityRequirements` 表示当前对接强制要求的认证方式。`securityRequirements` 为空时不启用内置凭证认证，但 `AuthProvider` 仍会被调用
- 只配置 `AuthProvider` 时，它可以作为唯一认证来源，即使 `securityRequirements` 非空
- 如果同时配了凭证文件和 `AuthProvider`，两者分别生成 Header 后合并；同名不同值会 fail-fast
- 认证失败时（如 token 获取异常），抛出的异常会传播到 `send()` 方法，请求会被拦截，不会发出
## 6. AgentCard 定义

AgentCard 通过 `capabilities.extensions` 声明扩展点：

```json
{
  "name": "SPN Domain Agent",
  "capabilities": {
    "streaming": true,
    "extensions": [
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
        "description": "结构化任务提示",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1",
        "description": "协商文本交换",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
        "description": "授权白名单",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
        "description": "结果通知订阅",
        "required": false
      }
    ]
  },
  "securitySchemes": {
    "bearerAuth": {
      "type": "http",
      "scheme": "bearer"
    }
  },
  "securityRequirements": [
    {
      "schemes": {
        "bearerAuth": []
      }
    }
  ],
  "supportedInterfaces": [
    {
      "protocolBinding": "HTTP+JSON",
      "protocolVersion": "1.0",
      "url": "https://127.0.0.1:26335/a2a/json"
    }
  ]
}
```

扩展 URI 必须与 A2A-T 定义完全一致。

`securitySchemes` 与 `securityRequirements` 都是可选字段。前者表示智能体支持的认证方式，后者表示当前对接强制要求的认证方式；`securityRequirements: []` 表示不启用内置凭证认证。

## 7. A2A-T 扩展能力

引擎自动处理四个 A2A-T 扩展，你无需关心协议细节：

### Task-T（自动）

`onTask` 通过 `TaskSubmission.fromText(..., templateUri)`、
`TaskSubmission.fromUnclassifiedText(...)` 或 `TaskSubmission.fromData(...)` 明确选择输入轨道，
再交给 `TaskDispatcher.dispatch(...)`。工作流已经知道投诉模板时必须传入模板；只有宿主尚未完成
场景分类时才使用 `fromUnclassifiedText` 让 SDK 识别场景。引擎自动调用匹配的 A2A-T SDK 方法并注入 metadata。

### Negotiation-T（自动）

智能体返回 `INPUT_REQUIRED` 时，引擎构造 `NegotiationRequest`，调用你的 `onNegotiation()` 获取
Accept/Reject/Abort 强类型决策，然后调用对应 SDK fromText/fromData 方法并发回后续消息。自动循环最多
`maxNegotiationRounds` 次（默认 3）。

### Authorization-T（独立授权操作）

工作台在需要管理抢通白名单时单独调用，不作为工作流节点。Authorization-T 使用专用
transport/runtime/context：

```java
ExtensionSender authorizationSender = new DefaultExtensionSender(authorizationTransport);
authorizationSender.sendExtensionMessageFromData(
    "SPN Domain Agent",
    "Authorization-T 结构化独立操作",
    authorizationData,
    authorizationSchema,
    A2ATExtension.AUTHORIZATION_T).join();
```

内部使用 `A2ATExtension.AUTHORIZATION_T`，勿硬编码 URI。SPN 智能体收到后存储策略，后续操作与白名单比对，在策略内直接执行，不在则拒绝。

### Notification-T（独立长连接订阅）

工作台在需要订阅抢通结果时单独调用，使用与 Task-T、Authorization-T 不同的
transport/runtime/context：

```java
NotificationSubscription subscription = notificationSender.openNotificationFromData(
    "SPN Domain Agent",
    "Notification-T subscription",
    notificationData,
    notificationSchema,
    event -> {
        if ("recovery-result".equals(event.get("artifact_name"))) {
            persistRecoveryResult(event);
        }
    }).join();
SendMessageResult ack = subscription.acknowledgement().join();
```

ACK 仅表示订阅已建立。工作台应保留 `NotificationSubscription`，收到预期的
`recovery-result`、取消或关闭服务时调用幂等 `close()`。单次工作流完成不关闭该通道。

## 8. HTTPS 配置

```java
// 仅用于受控的本地诊断：跳过证书链校验，但仍校验主机名
.sslVerify(false)

// 生产环境：启用验证 + 自定义 CA 证书
.sslVerify(true)
.caCertsPath("/path/to/ca-certs.pem")

// 可选：mTLS 与 CRL。私钥支持 PKCS#8 PEM/DER；加密私钥需提供密码
.clientCertPath("/path/to/client-cert.pem")
.clientKeyPath("/path/to/client-key.pem")
.clientKeyPassword("change-me")
.crlPath("/path/to/revocations.crl")
```

HTTP/JSON-RPC 的 TLS 策略只作用于当前客户端，不修改 JVM 全局主机名校验设置；关闭证书链校验时仍会加载 mTLS
客户端身份。生产环境应保持 `sslVerify(true)`；自签证书通过 `caCertsPath` 建立信任。默认 gRPC runtime 在
`sslVerify(false)` 时使用 plaintext，因此不能同时配置 mTLS 或 `crlPath`，这些组合会 fail-fast。

## 9. 日志

引擎设有专用 `PROTOCOL` 日志器输出协议层请求/响应摘要。Body 默认禁用；
`Authorization`、Cookie、API Key、Token、Secret 等敏感 Header 默认脱敏。只能在受控联调环境临时开启 DEBUG 和 body：

```properties
logger.protocol.name=PROTOCOL
logger.protocol.level=DEBUG
# 复用 root 的 console/file appender；不要在没有正确 appenderRef 时设为 false
logger.protocol.additivity=true
```

以下环境变量或同名 JVM system property 控制内容：

```properties
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY=true
WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS=100000
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS=false
```

只有在隔离、受控的本地联调中才可临时打开敏感 Header；打开时引擎会打印安全告警。

## 10. 事件回调

订阅执行事件实现实时监控：

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case EventType.STEP_START -> System.out.println("步骤开始: " + data.get("step"));
            case EventType.AGENT_STATUS_UPDATE -> System.out.println(
                    data.get("agent") + " 状态: " + data.get("state"));
            case EventType.NEGOTIATION_REQUEST -> System.out.println(
                    "协商请求来自 " + data.get("agent"));
            case EventType.COMPLETE -> System.out.println("工作流执行完成");
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

常用事件类型：`STEP_START`、`STEP_COMPLETE`、`AGENT_REQUEST`、`AGENT_RESPONSE`、`NEGOTIATION_REQUEST`、`NEGOTIATION_RESOLVED`、
`COMPLETE`、`ERROR`。

## 11. 从编排中心加载工作流

```java
// 按意图搜索
List<WorkflowSearchResult> results = LoadPsop.search(
                "https://127.0.0.1:5001", "SPN跨城专线故障诊断", 5, null, false);

// 按 ID 加载完整工作流
Workflow workflow = LoadPsop.load(
        "https://127.0.0.1:5001", results.get(0).getWorkflowId(), null, false);
```

## 12. 自定义扩展

如需扩展新的 A2A-T 扩展点，实现 `ExtensionHandler` 接口：

```java
public class MyExtensionHandler implements ExtensionHandler {
    @Override
    public String extensionKeyword() {
        return "My-Extension";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint) {
        metadata.put("https://example.com/extensions/My-Extension/v1", "value");
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback) {
        return CompletableFuture.completedFuture(result);
    }
}
```

通过配置注册：

```java
WorkflowEngineClientConfig.builder()
    .customHandlers(List.of(new MyExtensionHandler()))
    .build();
```

## 13. 你需要使用的接口一览

| 接口/类                                                | 用途                                                          |
|--------------------------------------------------------|---------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | 工作流执行入口                                                |
| `ControlPoint` / `DefaultControlPoint`                 | 业务决策实现（onTask、onSelfTask、onRoute、onNegotiation 等） |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | 工作流发送（sendMessage、认证、扩展）                         |
| `ExtensionSender` / `DefaultExtensionSender`           | 前置授权请求与长连接通知订阅                                  |
| `A2ATransport`                                         | 共享通信层（A2A Java 客户端 runtime、认证、SSE 消费）         |
| `WorkflowEngineClientConfig`                           | 配置（SSL、认证、A2A-T、协商轮数、自定义 Handler）            |
| `AuthProvider`                                         | 自定义认证                                                    |
| `ExtensionHandler`                                     | 自定义扩展                                                    |
| `EventCallback` / `EventType`                          | 事件回调                                                      |
| `LoadPsop` / `RegistryClient`                          | 工作流加载 / AgentCard 获取                                   |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | 工作流定义                                                    |
| `ExecutionResult`                                      | 执行结果                                                      |
| `SendMessageResult` / `TaskResponse`                   | 消息/任务响应                                                 |
