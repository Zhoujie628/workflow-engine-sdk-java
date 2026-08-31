# A2A-T 工作流执行引擎 - 二次开发集成指南

## 1. 概述

A2A-T 工作流执行引擎是一个 Java SDK，用于基于 A2A 协议和 A2A-T 电信扩展编排多智能体工作流。

引擎处理 A2A 信封、消息收发、流式响应、协商关联、认证和 TLS；宿主负责最终内容生成、语义校验和业务决策。

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
字段与完整示例见 [业务回调集成契约](BUSINESS_CALLBACKS.md)。

```java
ControlPoint callbacks = ControlPoint.builder()
    .onTask(request -> CompletableFuture.completedFuture(
        MessageContent.text(request.getInstruction())))
    .onSelfTask(request -> CompletableFuture.completedFuture(
        TaskResult.success(List.of(Map.of(
            "sourceResults", request.getWorkflowInput().upstreamResults())))))
    .onRoute(request -> CompletableFuture.failedFuture(
        new IllegalStateException("Supply a routing policy for " + request.stepName())))
    .onNegotiation(request -> CompletableFuture.completedFuture(
        new NegotiationReply.Stop("manual.required", "Manual confirmation required")))
    .build();
```

### 4.4 执行

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(List.of(card1, card2))
        .controlPoint(new MyControlPoint())
        .runtimeIntent("SPN跨城专线故障诊断与抢通")
        .lang("zh")
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

引擎不读取 A2A-T .env，也不创建 LLM client。业务回调需要 A2A-T 时， 宿主用自己的环境文件初始化 A2ATClient/A2ATServer，配置
provider/model/key/base URL 和 A2AT_LANGUAGE。 样例的 a2atEnvPath 是宿主／Demo 配置，不是引擎 builder 参数。 OMC 凭据解密不与
LLM 配置耦合：内置凭据模式通过 WorkflowEngineClientConfig.builder ().credentialEncryptionKey (key) 显式提供密钥， 再将配置好的
engineClient 传给 ExecutePsop。自定义 AuthProvider 自行管理 token 和配置。 测试使用当前 SDK SPI 的离线
provider，不覆盖模板，也不是生产失败兜底。

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

生成密钥后由宿主安全保存，并显式传给 credentialEncryptionKey；下列环境变量也可由宿主读取：

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
- `securitySchemes` 表示智能体支持的认证方式；`securityRequirements` 表示当前对接强制要求的认证方式。
  `securityRequirements` 为空时不启用内置凭证认证，但 `AuthProvider` 仍会被调用
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

`securitySchemes` 与 `securityRequirements` 都是可选字段。前者表示智能体支持的认证方式，后者表示当前对接强制要求的认证方式；
`securityRequirements: []` 表示不启用内置凭证认证。

## 7. A2A-T 扩展能力

只有远端 `INPUT_REQUIRED` 携带有效 Negotiation-T Propose 才进入 `onNegotiation`。 终态不会重启协商，普通 INPUT_REQUIRED
明确报告不支持的交互。 宿主自行校验、理解 Propose，并用自己的 A2A-T client 生成最终 Accept/Reject/Abort。 通过
`A2atMessages.contextOf(request.received())` 取得收到的上下文； 结束回复保持相同 id、round、maxRounds，最后允许的一轮仍可回答，不自行
nextRound 或返回新 Propose。

返回 `new NegotiationReply.Send(content)` 发送最终内容； 返回 `new NegotiationReply.Stop(code, reason)` 只在本地停止，不生成
Abort。 同一任务／会话／轮次的重复等待事件不会重复回调、重复提交；未变化状态通过 getTask 观察。
`maxNegotiationExchanges` 默认 3，是独立于 SDK context.maxRounds 的本地交互资源预算。 超时、预算耗尽、回调缺失均明确失败，不默认
Accept，也不自动生成 Abort。 Accept/Reject 的 SUBMITTED/WORKING ACK 仍需等待任务结果，不重发原命令。 业务发送 Abort 后，即使远端用
COMPLETED 确认，也不能判为诊断成功。

```java
CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);

NotificationSubscription openNotification(String agentName, MessageContent content,
                                          BiConsumer<NotificationSubscription, ReceivedMessage> listener);
```

宿主生成最终 Authorization-T/Notification-T 内容后调用上述接口；使用三类独立 transport/runtime/context。订阅监听器收到
handle 与完整 ReceivedMessage，按业务收到抢通结果后关闭。handle.acknowledgement () 和 completion () 分别表示 ACK
和真实流退出，不用它们的成功作为工作流前提。

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

HTTP/JSON-RPC 的 TLS 策略只作用于当前客户端，不修改 JVM 全局主机名校验设置；关闭证书链校验时仍会加载 mTLS 客户端身份。生产环境应保持
`sslVerify(true)`；自签证书通过 `caCertsPath` 建立信任。默认 gRPC runtime 在
`sslVerify(false)` 时使用 plaintext，因此不能同时配置 mTLS 或 `crlPath`，这些组合会 fail-fast。

## 9. 日志

将 `PROTOCOL` logger 设为 DEBUG，可查看实际传输边界的观测记录。 HTTP/JSON-RPC 记录 A2A SDK 处理后的实际序列化正文和应用头；
A2A-Version 只在真实请求有该头时出现，不为日志展示补造 Header。 gRPC 记录实际 metadata 和 protobuf 的 JSON 展示，不伪装成
HTTP JSON 报文。 JDK 自动网络头、HTTP/2 帧、TLS 密文及服务端字节不在此观测范围。

dev 中 `ORDER_FORWARD_REQUEST` 表示交给东信 SDK 的请求，
`ORDER_SDK_RESPONSE` 表示 SDK 实际交付的状态、多值响应头和文本。
`sdk-sse-text` 从 SDK 字符串分片组装可见 SSE 帧；原始字节编码及平台到 OMC 的报文不可见， 不能称为 OMC 抓包。`MODEL_PREVIEW`
只是高层预览，默认关闭，不是协议证据。

```properties
logger.protocol.name=PROTOCOL
logger.protocol.level=DEBUG
logger.protocol.additivity=true
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY=true
WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS=100000
```

DEBUG 开启时正文观测默认开启；敏感部署可显式禁用。JVM 同名属性优先于环境变量。 认证头、Cookie、Token
及识别出的口令字段强制脱敏，不提供关闭脱敏的开关。 这是字段级脱敏，不能自动识别所有个人信息和业务敏感内容，生产应另定日志策略。
缓冲有界：原始收集器按该数值限制字节，输出文本按字符限制； 超限 SSE 帧整帧丢弃至下个分隔符并标记
dropped-capacity，禁用、截断、中断也有明确标记。 UTF-8 分片先组装再解码，日志观察失败不改变报文投递；文件引用不会为打印日志而下载。
requestId 关联单次调用；工作流调用还带 executionId/logicalTaskId/attempt、 agent/contextId/channel，已知远端任务时附
remoteTaskId，均为本地日志字段，不污染协议 metadata。

### 查看真实协商与协议日志

直接运行本地 SpringSpnDemo，默认 City1 缺参并协商、City2 参数完整直接诊断，无需添加 VM 参数。 Negotiation-T 扩展激活本身不强制协商；这是本地
Demo 专门设置的场景，不是引擎默认行为。 要关闭本地缺参演示、让两城市都直接诊断，在 IDEA **VM options** 增加：

```text
-Da2at.samples.negotiation=false
```

默认仅移除 City1 的 Task-T 任务对象；启用状态下增加 `-Da2at.samples.negotiation.city=city2` 或 `both`
可演示 City2 或两城市同时缺参。宿主仍保留各城市的正确输入，原投诉上下文不变。预期链路是 `DEMO_NEGOTIATION` →
`INPUT_REQUIRED / PROPOSE`
→ 工作台 onNegotiation → `ACCEPT` → 两城市完成 → 一次汇总。 非内嵌 OMC 模式默认不注入缺参，显式设置
`-Da2at.samples.negotiation=true` 也会被拒绝。 Demo 将开关传入当前 Spring 应用实例，不设置或修改 JVM 全局开关，不影响其他宿主。
协议日志在控制台及以运行目录为基准的 `logs/spn-demo.log`。 main 支持直连；dev 默认 order，两种模式使用同一业务回调。

离线重复验证（两个命令顺序执行，避免端口冲突）：

```powershell
mvn -q -pl samples -am "-Dtest=SpringSpnDemoE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
# 仅 dev：真实供应商 jar + 本地平台/OMC 模拟器
mvn -q -pl samples -am "-Dtest=SpringSpnDemoOrderE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

每个测试类覆盖无 VM 参数的默认单城市协商、显式关闭、显式开启及两城市同时缺参。测试使用离线 LLM provider，但实际运行当前 SDK
的 模板、校验和 HTTP/SSE；不是现网 OMC、平台或真实模型语义的验证。

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

直接构造最终 MessageContent (parts, metadata, extensions)，由宿主管理扩展内容的生成／校验。无需注册引擎 handler 或 SDK
实例。A2atMessages.from 是 A2A-T metadata 复制辅助；非 A2A-T 扩展也可直接提供 metadata 和激活 URI。引擎不会仅因 AgentCard
声明而自动生成内容。

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
| `EventCallback` / `EventType`                          | 事件回调                                                      |
| `LoadPsop` / `RegistryClient`                          | 工作流加载 / AgentCard 获取                                   |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | 工作流定义                                                    |
| `ExecutionResult`                                      | 执行结果                                                      |
| `SendMessageResult` / `TaskResult`                     | 消息/任务响应                                                 |
