# WAIMO 接入工作台实施指南

> 本文是 `dev` 分支的场景专属材料，不属于通用发布分支。它说明 WAIMO 如何通过 MSB 向工作台发送 Task-T，并给出可落地到接口实现的 Java 代码结构。

## 1. 实现边界

WAIMO 侧需要完成四件事：

1. 从注册中心取得工作台 AgentCard，并把其中的服务地址替换为 MSB 暴露的 A2A 基础地址。
2. 使用 A2A-T SDK 的 `A2ATClient` 根据业务数据和 schema 生成 Task-T 内容。
3. 注入能够把 A2A 标准冒号动作路径改写为 MSB 斜杠路径的 `A2AJavaClientRuntime`。
4. 调用执行引擎 `WorkflowEngineClient.sendTask(...)`，等待任务完成并处理标准 A2A 错误、任务失败或协商。

各组件职责如下：

| 组件 | 职责 |
| --- | --- |
| `A2ATClient` | 根据自然语言或结构化业务数据生成 Task-T；生成 Negotiation-T 回复 |
| `WorkflowEngineClient` | 发送任务，维持 task/context 关联，处理 SSE、任务状态和协商交换 |
| `A2AJavaClientRuntime` | 执行实际 A2A 网络调用；可以替换或装饰 HTTP 通道 |
| `AuthProvider` | 按请求提供 MSB/工作台需要的认证 Header |
| 工作台 `A2ATServer` | 校验入站 Task-T 并提取结构化业务参数 |
| 工作流执行引擎 | 工作台检索到 PSOP 后负责 DAG 调度，不参与 WAIMO 的 Task-T 内容决策 |

WAIMO 不应手写 A2A JSON 信封，也不应直接调用执行引擎内部的 dispatch 方法。

## 2. 版本基线

当前 `dev` 分支使用以下版本：

```xml
<properties>
    <workflow-engine.version>1.0.0</workflow-engine.version>
    <a2a-t.version>1.1.0</a2a-t.version>
</properties>

<dependencies>
    <dependency>
        <groupId>net.openan.workflow.sdk</groupId>
        <artifactId>workflow-engine</artifactId>
        <version>${workflow-engine.version}</version>
    </dependency>
    <dependency>
        <groupId>net.openan.a2a-t.sdk</groupId>
        <artifactId>a2a-t-client</artifactId>
        <version>${a2a-t.version}</version>
    </dependency>
</dependencies>
```

实际工程应统一管理版本，不要同时引入不同版本的 A2A Java SDK。`workflow-engine` 已经声明其运行所需的 A2A Java SDK 依赖。

## 3. AgentCard 与 MSB 地址

从注册中心取得 AgentCard 后，只替换 `supportedInterfaces[].url` 中的基础地址。不要把 `/message/stream` 写进 AgentCard。

例如，MSB 对外地址为：

```text
https://msb.example/bgw/cswg-service/a2a/json
```

执行引擎和 A2A Java SDK 会在该基础地址后拼接动作路径。WAIMO 的自定义 Runtime 再将最终请求改写为：

```text
POST https://msb.example/bgw/cswg-service/a2a/json/message/stream
```

AgentCard 替换示例：

```java
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;

AgentCard registeredCard = registryClient.getAgentCard(workbenchAgentId);

AgentCard workbenchCard =
    AgentCard.builder(registeredCard)
        .supportedInterfaces(
            List.of(
                new AgentInterface(
                    "HTTP+JSON",
                    "https://msb.example/bgw/cswg-service/a2a/json")))
        .build();
```

替换后仍应校验：

- `AgentCard.name` 与调用 `sendTask` 时使用的名称完全一致；
- `capabilities.streaming=true`；
- `capabilities.extensions` 声明 Task-T；
- MSB 已发布 `/message/stream`，并按原样转发 A2A Header、请求体和 SSE 响应。

如果还要使用取消和订阅能力，MSB 还需要发布：

```text
POST /tasks/{id}/cancel
POST /tasks/{id}/subscribe
```

普通查询接口没有冒号，不需要改写：

```text
GET /tasks/{id}
GET /tasks
```

## 4. 实现 MSB 斜杠路径 Runtime

A2A 标准 REST 动作路径使用冒号。MSB 暂时不能发布包含冒号的接口，因此只在 WAIMO 的网络适配层做以下映射：

| A2A Java SDK 生成的路径 | MSB 发布路径 |
| --- | --- |
| `/message:send` | `/message/send` |
| `/message:stream` | `/message/stream` |
| `/tasks/{id}:cancel` | `/tasks/{id}/cancel` |
| `/tasks/{id}:subscribe` | `/tasks/{id}/subscribe` |

WAIMO 应实现 `DefaultA2AJavaClientRuntime` 的 HTTP 装饰扩展点：

```java
public final class MsbA2AJavaClientRuntime extends DefaultA2AJavaClientRuntime {

    public MsbA2AJavaClientRuntime(
            boolean sslVerify,
            String caCertsPath,
            long sendTimeoutSeconds,
            String preferredProtocol) {
        super(sslVerify, caCertsPath, sendTimeoutSeconds, preferredProtocol);
    }

    @Override
    protected A2AHttpClient customizeHttpClient(A2AHttpClient delegate) {
        return new SlashActionHttpClient(delegate);
    }
}
```

`SlashActionHttpClient` 必须装饰 `A2AHttpClient` 的 GET、POST、DELETE builder，在其 `url(String)` 方法中改写动作路径，其他方法全部委托给原对象。完整实现参考：

- [`SlashActionA2AJavaClientRuntime`](../../../samples/src/main/java/dev/openan/workflow/engine/examples/gateway/SlashActionA2AJavaClientRuntime.java)
- [`SlashActionA2AJavaClientRuntimeTest`](../../../samples/src/test/java/dev/openan/workflow/engine/examples/gateway/SlashActionA2AJavaClientRuntimeTest.java)

该适配器只允许修改 URL，不能自行重新实现以下能力：

- A2A 消息序列化；
- SSE 解码、事件分发及连接生命周期；
- 标准 A2A 错误解析；
- TLS 和超时；
- A2A-Version、A2A-Extensions 和认证 Header 组装。

查询参数和 fragment 必须原样保留；非 A2A 动作路径必须保持不变。

## 5. 实现 Task-T 内容工厂

WAIMO 负责业务数据、schema 和模板选择。结构化数据场景使用：

```java
import dev.openan.workflow.engine.client.A2atMessages;
import dev.openan.workflow.engine.model.MessageContent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.openan.a2at.sdk.client.A2ATClient;
import org.a2aproject.sdk.spec.TextPart;

public final class WaimoTaskContentFactory {
    private final A2ATClient a2atClient;

    public WaimoTaskContentFactory(Path a2atEnvPath) {
        this.a2atClient = new A2ATClient(a2atEnvPath);
    }

    public MessageContent create(
            Map<String, Object> businessData,
            Map<String, Object> schema,
            String templateUri,
            String userVisibleText) {
        var generated =
            a2atClient.generateTaskPromptFromDataWithSchema(
                businessData, schema, templateUri);

        return A2atMessages.from(
            generated,
            List.of(new TextPart(userVisibleText)));
    }
}
```

这里的 `MessageContent` 已包含：

- Task-T URI 对应的 metadata；
- `templateUri`；
- Task-T extension 激活信息；
- 提供给 A2A 消息 `parts` 的业务可读文本。

不要在生成后手工拼接或改写 Task-T prompt。若输入来自自然语言，应选择 A2A-T SDK 对应的自然语言生成接口，但最终仍转换为同一个 `MessageContent` 后交给 `sendTask`。

## 6. 认证实现

认证由 WAIMO 注入 `AuthProvider`。如果所有任务、查询、取消和订阅请求使用同一种 Token，可复用同一个线程安全 TokenService：

```java
AuthProvider authProvider =
    (agentName, agentCard, headers) -> {
        String token = tokenService.getOrRefresh();
        headers.put("Authorization", "Bearer " + token);
    };
```

`AuthProvider.applyAuth(...)` 会在每次发送、查询、取消和订阅前执行。实现要求：

- Token 缓存和刷新必须线程安全；
- 不在日志中打印完整 Token；
- 获取 Token 失败时抛出明确异常，不发送无认证请求；
- 不要同时通过 credentials 配置和 `AuthProvider` 为同一个 Header 提供不同值，否则执行引擎会以 `SecurityException` 拒绝请求。

如果 MSB 和工作台分别需要不同认证信息，应由 `AuthProvider` 一次性生成两个不同名称的 Header，或者由 MSB 在可信边界内补充下游认证；不要让两套配置争用 `Authorization`。

## 7. 组装并发送任务

推荐把一次 WAIMO 业务任务对应的传输、Client 和协商状态放在同一个作用域内：

```java
public SendMessageResult submitTask(
        AgentCard workbenchCard,
        MessageContent taskContent,
        AuthProvider authProvider) {

    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .sslVerify(true)
            .caCertsPath("/opt/waimo/conf/msb-ca.pem")
            .sendTimeoutSeconds(600)
            .preferredProtocol("HTTP+JSON")
            .authProvider(authProvider)
            .build();

    A2AJavaClientRuntime runtime =
        new MsbA2AJavaClientRuntime(
            config.isSslVerify(),
            config.getCaCertsPath(),
            config.getSendTimeoutSeconds(),
            config.getPreferredProtocol());

    A2ATransport transport =
        new A2ATransport(List.of(workbenchCard), runtime, config);
    DefaultWorkflowEngineClient client =
        DefaultWorkflowEngineClient.owning(transport, config);

    try (client) {
        return client.sendTask(workbenchCard.name(), taskContent).join();
    }
}
```

注意：

- 业务只调用公开的 `sendTask`，不调用内部 dispatch；
- `sendTask` 的 `agentName` 必须匹配 AgentCard；
- `MessageContent` 若携带 Task-T metadata，就必须同时激活 Task-T extension；
- `sendTask` 返回前会持续处理同一远端任务的 SSE 状态和必要的 Negotiation-T 交换；
- 不要在任务仍执行时提前关闭 Client 或 Runtime；
- `DefaultWorkflowEngineClient.owning(...)` 会在 `close()` 时关闭传输，避免重复关闭资源。

## 8. Negotiation-T 回调

如果工作台可能返回 `INPUT_REQUIRED + Negotiation-T Propose`，WAIMO 必须给本次调用提供 `NegotiationStrategy`：

```java
NegotiationStrategy strategy = request -> {
    // 1. 从 request.received() 取得 Propose metadata 和 NegotiationContext。
    // 2. 使用 A2ATClient.validateProposePromptAndDataFilling(...) 校验并提取待补字段。
    // 3. 根据 WAIMO 的业务数据决定 Accept、Reject 或 Abort。
    // 4. 使用 A2ATClient 的对应生成接口构造回复。
    // 5. 使用 A2atMessages.from(...) 返回 MessageContent。
    return CompletableFuture.completedFuture(replyContent);
};

SendMessageResult result =
    client.sendTask(workbenchCard.name(), taskContent, strategy).join();
```

协商实现必须保留收到的 `NegotiationContext` 和当前 round。回复已有 Propose 时不能自行创建新的上下文或调用 `nextRound()`。可参考：

- [`NegotiationStrategy`](../../../samples/src/main/java/dev/openan/workflow/engine/examples/negotiation/NegotiationStrategy.java)

如果 WAIMO 能保证首次 Task-T 已包含工作台 schema 的全部必填字段，可以不传本次协商策略；但生产代码仍应明确约定收到 `INPUT_REQUIRED` 时的处理策略。

## 9. 工作台接收侧约定

MSB 将斜杠路径转发到工作台时，工作台 starter 必须显式启用服务端和斜杠别名：

```yaml
a2at:
  server:
    enabled: true
    path-prefix: /a2a/json
    slash-action-aliases-enabled: true
```

工作台收到 Task-T 后的正确顺序是：

1. A2A Server 接收请求并创建任务；
2. 使用 `A2ATServer.validateTaskPromptAndDataFilling(...)` 校验 Task-T；
3. 只使用校验后的结构化参数形成检索意图；
4. 从注册中心加载被调度智能体 AgentCard；
5. 从编排中心检索并加载 PSOP；
6. 调用 `ExecutePsop.execute()` 启动工作流；
7. 通过原 Task 的 SSE 流返回状态、协商或最终结果。

当前示例代码对应：

- 入站执行器：[`SpringWorkbenchExecutor`](../../../samples/src/main/java/dev/openan/workflow/engine/examples/workbench/SpringWorkbenchExecutor.java)
- Task-T 校验：[`WorkbenchTaskInputParser`](../../../samples/src/main/java/dev/openan/workflow/engine/examples/workbench/WorkbenchTaskInputParser.java)
- PSOP 检索和执行：[`WorkbenchOrchestrator`](../../../samples/src/main/java/dev/openan/workflow/engine/examples/workbench/WorkbenchOrchestrator.java)

传输 Header、A2A 信封等在任务创建前即可判定的错误，应返回标准 A2A error envelope。当前工作台是在 A2A Task 创建后进入 `AgentExecutor`，因此 `WorkbenchTaskInputParser` 发现的 Task-T 业务参数错误会通过原 SSE 流返回 Task `FAILED` 及失败原因，而不是把已经创建的任务改写成 HTTP 错误。

## 10. 协议日志与联调取证

WAIMO 应把名为 `PROTOCOL` 的 logger 配置为 DEBUG：

```yaml
logging:
  level:
    PROTOCOL: DEBUG
```

执行引擎默认对真实传输边界进行 pretty 展示。联调时应确认同一个 `requestId` 下依次存在：

```text
[DIRECT_HTTP] REQUEST_HEADERS
Target: POST https://msb.example/.../a2a/json/message/stream

=== Headers ===
A2A-Version: 1.0
A2A-Extensions: https://.../Task-T/v1
Content-Type: application/json
Accept: text/event-stream

[DIRECT_HTTP] REQUEST_BODY
=== Body ===
{
  "message": {
    ...
  }
}
```

为了快速区分 WAIMO 流量，可以在调用外层增加仅用于本地日志关联、不会写入协议报文的上下文：

```java
SendMessageResult result =
    WireLog.call(
        Map.of(
            "caller", "WAIMO",
            "flow", "waimo-to-workbench",
            "actionPathStyle", "slash"),
        () -> client.sendTask(workbenchCard.name(), taskContent))
    .join();
```

可用以下开关控制日志：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `WORKFLOW_ENGINE_PROTOCOL_PRETTY` | `true` | JSON pretty 展示 |
| `WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY` | `true` | 是否显示请求和响应 Body |
| `WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS` | `100000` | 单条 Body 最大展示字符数 |

认证 Header 和敏感字段会脱敏。日志是应用传输边界观察结果，不是 TLS 线上的抓包字节。

## 11. 返回值与异常处理

`sendTask` 有三类结果：

1. **任务成功**：`SendMessageResult.taskState` 为终态成功，业务读取 `text`、Task 或 metadata。
2. **任务创建后失败**：返回 A2A Task 终态 `FAILED`，失败原因位于状态消息或结果内容中。
3. **请求未创建任务即失败**：Future 以异常完成，底层标准 A2A error envelope 会映射为可识别的远端错误异常。

WAIMO 应记录远端状态码、A2A error code/status/message 和本地 `requestId`，但不能把 Token、密码或完整凭证写入业务日志。超时或主动取消时，如果已取得远端 `taskId`，执行引擎会尝试取消远端任务；取消任务不等同于 Negotiation-T Abort。

## 12. 联调验收清单

- [ ] AgentCard 基础 URL 指向 MSB，且没有预先拼接动作路径。
- [ ] 最终 URI 是 `/message/stream`，不是 `/message:stream`。
- [ ] 请求 Header 包含正确的 `A2A-Version`、`A2A-Extensions`、Content-Type 和认证信息。
- [ ] 请求 Body 是 A2A-T SDK 生成内容转换后的 A2A Message，不是手写 JSON。
- [ ] MSB 不修改 Task-T metadata、message/context/task 标识或 SSE data。
- [ ] 工作台启用了 `a2at.server.enabled` 和斜杠路径别名。
- [ ] 工作台先完成 Task-T 校验，再检索 PSOP 并启动执行。
- [ ] 普通成功、协商、任务失败、标准 A2A error、超时和取消路径均有测试。
- [ ] 协议日志可通过同一个 `requestId` 关联 URI、Header、Body 和响应。
- [ ] Client、Transport 和 Runtime 在任务终止后关闭，无遗留 SSE 连接。

## 13. Demo 对照入口

[`SpringSpnDemo.sendTaskToWorkbench()`](../../../samples/src/main/java/dev/openan/workflow/engine/examples/demo/SpringSpnDemo.java) 即 WAIMO 入口的可运行模拟：

```text
读取工作台 AgentCard
  -> A2ATClient 生成 Task-T
  -> 注入 SlashActionA2AJavaClientRuntime
  -> WorkflowEngineClient.sendTask
  -> MSB 风格 /message/stream
  -> 工作台校验 Task-T
  -> 检索并加载 PSOP
  -> 启动工作流
  -> 返回最终 Task 结果
```

该 Demo 的本地 E2E 能证明 SDK 调用链、路径改写和工作台处理逻辑；它不能替代 WAIMO、MSB、注册中心和工作台真实环境的联合验证。
