# 引擎核心代码检视报告

> **归档状态（2026-08-25）**：本文是旧 commit 的问题快照，不是当前代码说明。
> 文中 P0/P1/P2 问题已在未发布版整改，包括 per-client TLS、认证 fail-closed、SSE 原样解析、
> 有界线程池、runtime 复用、可配置 Spring 执行器、无轮询 DAG join、回调透传、凭证解密
> fail-closed、规范 URI 精确匹配、任务状态判定，以及真实 SDK 的 Task/Negotiation/Authorization/
> Notification 管线。当前契约请以 [API 参考](API_REFERENCE.md)、[架构设计](DESIGN.md)和
> [CHANGELOG](../../CHANGELOG.md) 为准。下文的旧接口名和行号仅用于追溯，不得复制到新实现。

> **检视范围**: `workflow-engine` 模块 + `spring-boot-starter` 模块
> **检视日期**: 2026-08-18
> **检视人**: AI 辅助 + 人工核验
> **源码版本**: main 分支 `4aec309` / dev 分支 `39118ca`

---

## P0: 安全 / 正确性问题

### P0-1: `sslVerify=false` 全局禁用 JVM 主机名验证

**文件**: `DefaultA2AJavaClientRuntime.java:105-108`, `SslContextFactory.java:167-168`

**代码**:
```java
// DefaultA2AJavaClientRuntime.java:108
System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

// SslContextFactory.java:168
System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
```

**WHY 需要优化**:

`jdk.internal.httpclient.disableHostnameVerification` 是一个 JVM 级别的系统属性,影响同一 JVM 中所有 `java.net.http.HttpClient` 实例。在 Spring Boot 工作台场景中,同一 JVM 可能运行多个 HTTP 客户端(工作台自身的 REST 调用、注册中心客户端、LLM API 调用等)。设置此属性后,**所有** HTTPS 连接都失去主机名验证,不仅是 A2A 通道。

具体风险:
- 攻击者可以注册任意域名的证书(只要 CA 链有效),JDK 不会校验证书中的 CN/SAN 与目标主机名是否匹配
- 这是一个中间人攻击向量:攻击者用合法 CA 签发的"其他域名"证书即可冒充目标服务器

JDK 在类加载时缓存此属性(`jdk.internal.net.http.common.Utils`),意味着即使后来设为 `false` 也不会生效——属性必须在任何 `HttpClient` 创建之前设置。

**HOW 优化方向**:

JDK `HttpClient` 没有提供 per-client 的主机名验证器 API。可选方案:
1. **生产环境强制 `sslVerify=true`**:在 `WorkflowEngineClientConfig` 中将 `sslVerify` 默认值从 `true` 保持,在 `DefaultA2AJavaClientRuntime` 构造时,如果 `sslVerify=false`,打印 **WARN** 日志明确告知影响范围,并建议仅在开发环境使用
2. **使用信任所有证书的 `SSLContext` 但不禁用主机名验证**:当前 `SslContextFactory.createTrustAll()` 已经创建了信任所有的 `TrustManager`(接受任何证书),这在开发环境足够。主机名验证可以通过自定义 `SSLParameters` 在 `HttpClient.newBuilder().sslParameters(...)` 中更精细地控制,但 JDK 的实现仍受系统属性影响
3. **文档约束**:在 `SslContextFactory.createTrustAll()` 的 Javadoc 中已加 WARN,但 `DefaultA2AJavaClientRuntime` 的构造函数 Javadoc 应明确说明"此设置影响整个 JVM"

**核验要点**: 确认生产环境是否曾经使用过 `sslVerify=false`。如果仅在开发/测试环境使用,风险可控,但仍建议加日志告警。

---

### P0-2: AgentCredentialService 登录端点永远使用 trust-all SSL

**文件**: `AgentCredentialService.java:78-81`

**代码**:
```java
// Disable TLS verification (mirrors Python's verify=False for login endpoints)
b.sslContext(SslContextFactory.createTrustAll());
this.httpClient = b.build();
```

**WHY 需要优化**:

`AgentCredentialService` 的 `login()` 方法向 credential config 中配置的 `login_url` 发送认证请求(用户名/密码),获取 Bearer Token。这个 HTTP 客户端**无条件使用 trust-all SSL**,不受引擎配置 `sslVerify` 的控制。

即使引擎配置 `sslVerify=true`(A2A 通道使用正式 TLS),**登录端点的凭证交换仍然不验证 TLS**。攻击者可以中间人攻击 login 端点,截获用户名和密码。

Python SDK 的对应实现确实也使用 `verify=False`,但 Python 的 `verify=False` 是 per-session 的(只影响 `httpx.AsyncClient` 实例),不影响同一进程中的其他 HTTP 客户端。Java 的 `SslContextFactory.createTrustAll()` 虽然也是 per-instance 的 SSLContext,但 `createTrustAll()` 内部又调用了 `System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true")`,触发了 P0-1 的全局问题。

**HOW 优化方向**:

1. `AgentCredentialService` 构造函数增加 `sslVerify` 和 `caCertsPath` 参数,根据引擎配置决定是否验证 TLS
2. `SslContextFactory.createTrustAll()` 移除 `System.setProperty` 调用,只保留 trust-all `TrustManager`(per-instance 的 SSLContext 已经足够信任所有证书,不需要全局禁用主机名验证)
3. 如果 login 端点使用自签证书,应配置 `ca_certs_path` 指向自签 CA,而非 trust-all

**核验要点**: 确认生产环境的 login_url 是否使用自签证书。如果是,应配置 CA 路径而非 trust-all。

---

### P0-3: Notification-T 超时处理器吞没真实错误

**文件**: `A2ATransport.java:445-454`

**代码**:
```java
return future.orTimeout(5, TimeUnit.SECONDS)
        .exceptionally(
                e -> {
                    log.warn(
                            "[Transport] Notification-T subscription: no event in 5s, assuming active (stream stays open)");
                    return SendMessageResult.builder()
                            .text("Subscribed (no-ack)")
                            .taskState("TASK_STATE_WORKING")
                            .build();
                });
```

**WHY 需要优化**:

`orTimeout(5, SECONDS)` 在 5 秒后触发 `TimeoutException`。但 `exceptionally(e -> ...)` 捕获**所有**异常,不仅仅是 `TimeoutException`。

如果 Notification-T 流在 5 秒内失败(网络中断、服务端 500 错误等),stream thread 会调用 `future.completeExceptionally(e)`。此时 `orTimeout` 返回的 future 也会以同样的异常完成。`exceptionally` 会捕获这个真实错误,打印"no event in 5s"日志,并返回 `SendMessageResult(text="Subscribed (no-ack)", taskState="TASK_STATE_WORKING")`——**将真实错误伪装成订阅成功**。

调用方收到 "TASK_STATE_WORKING" 后认为订阅成功,但实际 SSE 流已经断开。后续的恢复结果永远不会到达,且无人知道订阅已失败。

**HOW 优化方向**:

在 `exceptionally` 中区分 `TimeoutException` 和其他异常:
```java
.exceptionally(e -> {
    if (e instanceof TimeoutException) {
        log.warn("[Transport] Notification-T subscription: no event in 5s, assuming active");
        return SendMessageResult.builder()
                .text("Subscribed (no-ack)")
                .taskState("TASK_STATE_WORKING")
                .build();
    }
    // 真实错误,不吞没
    throw new CompletionException(e);
});
```

注意: `e` 可能被 `CompletableFuture` 包装为 `CompletionException`,需要 `e.getCause() instanceof TimeoutException` 判断。

**核验要点**: 确认 Notification-T 流在 5 秒内失败时,当前行为是否确实将错误吞没。可以在测试中模拟服务端立即返回 500,观察调用方收到的结果。

---

### P0-4: SSE compact JSON 破坏含空白字符的文本值

**文件**: `A2AController.java:125-135`

**代码**:
```java
String compact =
        json.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .reduce("", String::concat);
String sse =
        String.format(Locale.ROOT, "id:%d%n", seq.incrementAndGet())
                + "data:" + compact + "\n\n";
```

**WHY 需要优化**:

这段代码将 protobuf JSON 序列化的 pretty-printed 输出压缩为单行。`lines()` + `map(String::trim)` + `reduce("", String::concat)` 的组合有两个问题:

1. **trim 破坏值内空白**: pretty-printed JSON 中,字符串值内的前导/后导空格在 trim 后丢失。例如 `"text": " Authorization-T pre-positioned "` 会变成 `"text": "Authorization-T pre-positioned"`。虽然 JSON 标准说空白在结构上不显著,但 **A2A 协议传输的是语义内容**,文本值中的空格对 Agent 理解指令有语义意义。

2. **`reduce("", String::concat)` 的 O(n^2) 性能**: 对 n 行 JSON,`reduce` 会创建 n 个中间字符串,每个比前一个长。对于大的 Task 响应(含长文本 artifact),这会产生 O(n^2) 的内存分配和拷贝开销。

3. **`%n` 平台依赖**: `String.format("%n")` 在 Windows 上产生 `\r\n`,在 Unix 上产生 `\n`。虽然 SSE 规范允许 `\r\n` 和 `\n`,但 compact JSON 已被规范为 `\n`,两者不一致。

**HOW 优化方向**:

使用 protobuf 的紧凑打印器(如果 API 支持),或用 Jackson 重序列化:
```java
// 方案 A: protobuf printer 配置
String json = JsonFormat.printer().omittingInsignificantWhitespace().print(sr);

// 方案 B: Jackson 重序列化(保证值内空白不丢失)
ObjectMapper mapper = new ObjectMapper();
String compact = mapper.writeValueAsString(mapper.readTree(json));

// SSE 行终止符统一为 \n
String sse = "id:" + seq.incrementAndGet() + "\ndata:" + compact + "\n\n";
```

**核验要点**: 确认 protobuf `JsonFormat.Printer` 是否有 `omittingInsignificantWhitespace()` 方法。如果有,方案 A 最简洁。如果确认 A2A 协议中文本值的空白不影响语义,则当前实现可接受但应注释说明。

---

## P1: 生产就绪性 / 设计问题

### P1-1: `DefaultExtensionSender` 三个扩展 prompt 生成器是空桩

**文件**: `DefaultExtensionSender.java:247-266`

**代码**:
```java
private String generateNegotiationPrompt(String naturalLanguageInput) {
    if (transport.getA2atClient() == null) { return null; }
    return null;  // STUB
}
private String generateAuthorizationPrompt(String naturalLanguageInput) {
    if (transport.getA2atClient() == null) { return null; }
    return null;  // STUB
}
private String generateNotificationPrompt(String naturalLanguageInput) {
    if (transport.getA2atClient() == null) { return null; }
    return null;  // STUB
}
```

**WHY 需要优化**:

`DefaultExtensionSender.sendExtensionMessage()` (line 70-81) 调用 `generateExtensionPrompt()` 来生成结构化的扩展 prompt。当返回 `null` 时,fallback 使用原始自然语言文本作为 metadata value。

只有 Task-T 的 `generateTaskPromptText()` 调用了 `a2atClient.generateTaskPrompt()`。其他三个扩展的 prompt 生成器无条件返回 `null`,即使 `A2ATClient` 已经初始化。

这意味着:
- **Authorization-T 预定位**: 工作台发送的授权白名单文本是原始自然语言,没有经 LLM 加工为结构化 prompt
- **Notification-T 预定位**: 同上
- **Negotiation-T 跟进**: 协商跟进消息的 metadata 是 fallback 文本

**HOW 优化方向**:

需要确认 A2A-T SDK 的 `A2ATClient` 是否提供以下方法:
- `generateNegotiationPrompt(String input) -> PromptGenerationResult`
- `generateAuthorizationPrompt(String input) -> PromptGenerationResult`
- `generateNotificationPrompt(String input) -> PromptGenerationResult`

如果 SDK 已有这些方法,直接调用(与 `generateTaskPromptText` 相同模式)。
如果 SDK 尚未提供,在方法 Javadoc 中标注"等待 SDK 支持",并在 `DefaultExtensionSender` 的类级 Javadoc 中更新说明。

**核验要点**: 检查 `a2a-t-sdk-java` 的 `A2ATClient` 类的公开 API,确认是否存在这三个方法或等价方法。

---

### P1-2: `A2ATransport` 使用无界 CachedThreadPool

**文件**: `A2ATransport.java:82-88`

**代码**:
```java
private final ExecutorService asyncExecutor =
        Executors.newCachedThreadPool(
                r -> {
                    Thread t = new Thread(r, "engine-send");
                    t.setDaemon(true);
                    return t;
                });
```

**WHY 需要优化**:

`newCachedThreadPool` 没有线程数上限。每个 `send()` 调用通过 `CompletableFuture.supplyAsync(..., asyncExecutor)` 提交任务。在并行 DAG 执行中(`WorkflowExecutor` 同时执行多个 ready step),如果多个 step 同时向多个 agent 发送消息,线程数可能短暂激增。

每个线程默认占用约 1MB 栈空间。20 个并行 agent 调用 = 20 个线程 = 20MB 栈内存。在生产环境中,工作台可能同时处理多个工作流实例,线程数可能进一步增长。

此外,`DefaultA2AJavaClientRuntime` 也有一个无界的 `httpClientExecutor` (line 81-87),以及 `sendNotificationStream` 创建的独立 `Thread` (line 372-444)。三类线程池/线程叠加,资源消耗可能不可控。

**HOW 优化方向**:

```java
// 有界线程池,可配置
private final ExecutorService asyncExecutor =
        new ThreadPoolExecutor(
                4, 16,  // core=4, max=16 (可配置)
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "engine-send"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略:调用方执行
        );
```

线程池大小应通过 `WorkflowEngineClientConfig` 外部化配置。

**核验要点**: 确认当前并行 DAG 执行的最大并发 agent 调用数。如果 PSOP 中的 `ALL_SUCCESS` step 最多有 2-3 个 subtask,16 个线程已经足够。

---

### P1-3: `DefaultA2AJavaClientRuntime` 每次 send 创建新 Client 和 HttpClient

**文件**: `DefaultA2AJavaClientRuntime.java:272, 315-341, 437-450`

**代码**:
```java
// sendMessage 中:
Client client = createClient(agentCard, agentUrl);  // 每次新建
// ...
client.close();

// createClient -> createHttpClient:
private A2AHttpClient createHttpClient() {
    if (this.sslVerify) {
        return new JdkA2AHttpClient();  // 每次新建 HttpClient
    }
    SSLContext trustAllCtx = SslContextFactory.createTrustAll();
    HttpClient httpClient = HttpClient.newBuilder()...build();  // 每次新建
    return new JdkA2AHttpClient(httpClient);
}
```

**WHY 需要优化**:

每次 `sendMessage` 都创建新的 `Client` 和新的 `HttpClient`。`HttpClient` 内部维护连接池、SSL context、selector 管理器等,创建成本高。JDK `HttpClient` 文档明确建议复用实例。

在协商循环中,一次 `sendMessage` 可能触发 3 轮协商(每轮一个 `sendMessage`),每轮都创建/销毁一个 `HttpClient`。连接无法复用,TCP/TLS 握手每次重新进行。

`HttpClient` 没有显式 `close()` 方法(Java 11-17),其内部资源依赖 GC 回收或 `executor` 的 shutdown。大量短生命周期 `HttpClient` 实例可能导致 `Selector` 管理器泄漏(JDK 内部每个 `HttpClient` 有一个 selector,通过弱引用清理,但 GC 压力大)。

**HOW 优化方向**:

```java
// 按协议+SSL配置缓存 Client 实例
private final Map<String, Client> clientCache = new ConcurrentHashMap<>();

private Client getOrCreateClient(AgentCard agentCard) {
    String cacheKey = agentCard.supportedInterfaces().get(0).url()
            + "|" + sslVerify + "|" + preferredProtocol;
    return clientCache.computeIfAbsent(cacheKey, k -> createClient(agentCard));
}
```

注意: 缓存 `Client` 后,`client.close()` 不应在每次 `sendMessage` 后调用。需要在 `DefaultA2AJavaClientRuntime.close()` 中统一关闭。

**核验要点**: 确认 a2a-java SDK 的 `Client` 是否线程安全,是否支持并发 `sendMessage` 调用。如果 SDK 内部不支持,缓存可能导致并发问题。

---

### P1-4: `A2AAutoConfiguration` 超时和线程池参数硬编码

**文件**: `A2AAutoConfiguration.java:106-107, 146-157`

**代码**:
```java
case "a2a.blocking.agent.timeout.seconds" -> "30";
case "a2a.blocking.consumption.timeout.seconds" -> "5";
// ...
new ThreadPoolExecutor(
        8, 8,  // 固定 8 线程
        0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(), ...);
```

**WHY 需要优化**:

1. **Agent 超时 30 秒**: 某些 Agent 执行复杂诊断可能需要 60-120 秒。30 秒太短,可能导致 Task 被中断。
2. **Consumption 超时 5 秒**: SSE 流式响应中,两个 event 之间的间隔可能超过 5 秒(LLM 思考时间)。5 秒太短。
3. **线程池固定 8 线程**: 无法根据工作台的并发需求调整。高并发场景下,第 9 个请求会阻塞在队列中(`LinkedBlockingQueue` 无界,但只有 8 个消费线程)。

这些参数不可通过 `application.yml` 配置,集成方必须覆盖 Bean 才能修改。

**HOW 优化方向**:

扩展 `A2AProperties`:
```java
@ConfigurationProperties(prefix = "a2at.server")
public class A2AProperties {
    // 现有字段...
    private int agentTimeoutSeconds = 30;
    private int consumptionTimeoutSeconds = 5;
    private int executorPoolCore = 8;
    private int executorPoolMax = 8;
}
```

`A2AConfigProvider` 和 `agentExecutorPool` 读取这些属性值。

**核验要点**: 确认 a2a-java SDK 的 `A2AConfigProvider` 支持哪些配置 key。如果 SDK 有其他 key,也应暴露。

---

### P1-5: `A2AProperties` 缺少工作台/网关配置项

**文件**: `A2AProperties.java` (仅 54 行)

**WHY 需要优化**:

`spring-boot-starter` 的 `@ConfigurationProperties` 只有 `a2at.server.agent-card` 和 `a2at.server.path-prefix` 两个属性。以下配置散落在 `samples` 模块的 `@Value` 注解中:

| 配置项 | 当前位置 | 应归入 |
|--------|---------|--------|
| `a2a.transport-mode` | `ClientRuntimeFactory` | `A2AProperties` |
| `a2a.order.host` | `ClientRuntimeFactory` | `A2AProperties` |
| `a2a.order.port` | `ClientRuntimeFactory` | `A2AProperties` |
| `a2a.order.simulator-enabled` | `ClientRuntimeFactory` | `A2AProperties` |
| `a2a.ssl-verify` | `ClientRuntimeFactory` | `A2AProperties` |
| `a2a.credentials-path` | `SpringWorkbenchExtensionLifecycle` | `A2AProperties` |
| `a2a.a2at-env-path` | 多处 | `A2AProperties` |

集成方使用 `spring-boot-starter` 时,无法通过统一的 properties 管理这些配置,必须查看 sample 源码才能知道有哪些 `@Value` 属性。

**HOW 优化方向**:

在 `spring-boot-starter` 中新增 `WorkflowProperties` 或扩展 `A2AProperties`:
```java
@ConfigurationProperties(prefix = "a2a")
public class WorkflowProperties {
    private String transportMode = "direct";
    private boolean sslVerify = true;
    private String credentialsPath;
    private String a2atEnvPath;
    // ... Order SDK 配置
}
```

注意: `spring-boot-starter` 不应依赖 `order-shaded-client`,因此 Order SDK 特有配置应放在 samples 层的 properties 中,或通过条件化配置实现。

**核验要点**: 确认工作台集成方实际需要配置哪些项。有些配置(如 `a2a.order.host`)是东信特有的,不应放在通用 starter 中。

---

### P1-6: `WorkflowExecutor` 延迟步骤使用 50ms sleep 轮询

**文件**: `WorkflowExecutor.java:205-218`

**代码**:
```java
if (readySteps.isEmpty()) {
    if (!deferredSteps.isEmpty()) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .thenCompose(v -> executeSteps(pending, executed, failed, deferCount));
    }
    return CompletableFuture.completedFuture(null);
}
```

**WHY 需要优化**:

当所有剩余步骤的前置依赖尚未完成时,执行器在一个 `runAsync` 线程上 sleep 50ms 然后重试。这是一个忙等待轮询循环。

问题:
1. **延迟**: 每次轮询间隔 50ms,如果依赖在 sleep 后 1ms 完成,仍需等待 49ms。对于 10 层依赖链,最坏情况额外 500ms 延迟。
2. **线程占用**: 每次轮询占用一个 `ForkJoinPool.commonPool` 线程 50ms。如果多个工作流并行执行,common pool 可能被占满。
3. **无退出条件检查**: 如果 `deferredSteps` 永远无法满足(依赖图中存在循环依赖或缺失依赖),这个循环会无限运行(虽然有 `dc > workflow.getSteps().size()` 的保护,但需要 `workflow.getSteps().size()` 次迭代才触发)。

**HOW 优化方向**:

用事件驱动替代轮询。当 `stepOutputs.put()` 完成一个步骤时,直接唤醒等待该步骤的 deferred steps:
```java
// 在 executeStep 完成后,直接将新的 ready steps 加入 pending,
// 而不是 sleep + 重试整个 pending 队列
// 当 readySteps 为空但 deferredSteps 非空时,
// 检查 deferredSteps 是否有步骤的前置已全部满足
```

或者使用 `CompletableFuture` 依赖图:每个 step 的 future 依赖其前置 step 的 future,用 `thenCompose` 链式组合,完全消除轮询。

**核验要点**: 确认当前 PSOP 工作流的依赖深度。如果大多数工作流只有 2-3 层,50ms 延迟可接受。如果有 5+ 层链式依赖,应优化。

---

### P1-7: `ExtensionSender` 接口默认 `sendNotification` 丢弃回调

**文件**: `ExtensionSender.java:70-82`

**代码**:
```java
default CompletableFuture<SendMessageResult> sendNotification(
        String agentName,
        String instruction,
        String naturalLanguageInput,
        Consumer<Map<String, Object>> eventCallback) {
    return sendExtensionMessage(
            agentName, instruction, naturalLanguageInput, A2ATExtension.NOTIFICATION_T);
    // eventCallback 被忽略!
}
```

**WHY 需要优化**:

接口的 default 方法接受 `eventCallback` 参数但**完全忽略它**——直接委托给 `sendExtensionMessage` 而不传递回调。`sendExtensionMessage` 不接受回调参数,Notification-T 的后续事件无处可去。

`DefaultExtensionSender` 重写了此方法(line 122-164),正确处理了回调。但如果其他实现类使用 default 方法(不重写),回调会被静默丢弃,调用方以为注册了回调但永远不会收到事件。

**HOW 优化方向**:

方案 A: 将 `sendNotification` 声明为抽象方法(去掉 default),强制实现类处理回调:
```java
CompletableFuture<SendMessageResult> sendNotification(
        String agentName, String instruction, String naturalLanguageInput,
        Consumer<Map<String, Object>> eventCallback);
```

方案 B: default 方法抛出 `UnsupportedOperationException`,提示实现类必须重写:
```java
default CompletableFuture<SendMessageResult> sendNotification(..., Consumer<Map<String, Object>> eventCallback) {
    throw new UnsupportedOperationException("sendNotification with callback must be overridden");
}
```

**核验要点**: 确认是否有除 `DefaultExtensionSender` 之外的其他 `ExtensionSender` 实现。如果没有,此问题可降低优先级。

---

## P2: 健壮性 / 边界场景

### P2-1: `CredentialCrypto.decryptIfNeeded` 解密失败时返回密文

**文件**: `CredentialCrypto.java:108-113, 132-135`

**代码**:
```java
// Key 未设置时:
log.warn("Encrypted value found but {} not set, using as-is", ENV_KEY);
return value;  // 返回 "enc:xxx:yyy"

// 解密异常时:
log.error("[CredentialCrypto] Decryption failed: {}", e.getMessage());
return value;  // 返回 "enc:xxx:yyy"
```

**WHY 需要优化**:

当解密失败(密钥错误、密文损坏、密钥未设置)时,方法返回 `enc:base64:base64` 字符串。这个字符串会被用作 Agent 的 Bearer Token 或密码发送给 login 端点。login 端点会认证失败,返回 401/403,但错误信息是"认证失败",不是"凭证解密失败"。

运维人员看到的是 "Auth login failed: status=401",需要追踪到 `CredentialCrypto` 的 WARN/ERROR 日志才能找到根因。如果日志级别设置不当(WARN 被过滤),根因完全隐藏。

**HOW 优化方向**:

解密失败时抛出异常而非返回密文:
```java
throw new IllegalStateException(
    "Credential decryption failed: " + e.getMessage()
    + ". Check A2AT_CRED_KEY environment variable.");
```

或者返回 `null`,让 `AgentCredentialService.login()` 的 `credential == null` 检查自然失败,但日志更清晰。

注意: 改为抛异常可能影响现有行为——如果某些凭证确实是明文(不以 `enc:` 开头),不受影响。但如果有配置文件混用了明文和密文,需要确保所有密文都能正确解密。

**核验要点**: 确认现有凭证配置文件中是否有 `enc:` 前缀的值。如果没有,此问题不影响当前运行,但仍应修复以防未来使用密文时出错。

---

### P2-2: `AgentAuthManager.setHttpClient()` 是空操作

**文件**: `AgentAuthManager.java:151-159`

**代码**:
```java
public void setHttpClient(java.net.http.HttpClient httpClient) {
    for (AgentCredentialService svc : services.values()) {
        if (svc != null) {
            // AgentCredentialService stores httpClient internally
            // New services created after this call will use default client
        }
    }
    log.info("[Auth] HTTP client propagated to {} service(s)", services.size());
}
```

**WHY 需要优化**:

方法遍历 `services` 但循环体为空(只有注释)。日志输出 "propagated to N service(s)" 但实际什么都没做。调用方以为 HTTP 客户端已传播到所有 credential service,但实际上每个 `AgentCredentialService` 仍然使用自己创建的 trust-all `HttpClient`。

这是一个误导性 API:方法签名和日志暗示功能存在,但实际未实现。

**HOW 优化方向**:

方案 A: 实现传播逻辑:
- `AgentCredentialService` 增加 `updateHttpClient(HttpClient)` 方法
- `setHttpClient` 调用每个 service 的 `updateHttpClient`
- 注意线程安全:`AgentCredentialService.httpClient` 字段需要 `volatile` 或同步

方案 B: 删除方法和日志(如果不打算实现):
- 移除 `setHttpClient` 方法
- 移除相关 Javadoc 中对它的引用

**核验要点**: 确认是否有调用方使用 `setHttpClient`。搜索 `authManager.setHttpClient` 的调用点。

---

### P2-3: `ExtensionRegistry` 使用 `contains()` 匹配扩展 URI

**文件**: `ExtensionRegistry.java:72`

**代码**:
```java
if (uri.toLowerCase().contains(entry.getKey().toLowerCase())
        && !seen.contains(entry.getKey())) {
```

**WHY 需要优化**:

使用 `String.contains()` 而非精确匹配来关联扩展 URI 与 handler。当前能工作是因为:
- `"https://projects.tmforum.org/.../Task-T/v1".toLowerCase()` 包含 `"task-t"`
- `"https://projects.tmforum.org/.../NEGOTIATION-T".toLowerCase()` 包含 `"negotiation-t"`

但 `contains` 是宽松匹配。如果未来出现名为 `"Task-T-Extended"` 的扩展 URI,它也会匹配 `"Task-T"` handler,导致错误的 handler 被调用。

**HOW 优化方向**:

改为精确匹配扩展 URI 中的路径段:
```java
// 提取 URI 最后一个路径段(如 "Task-T" 或 "NEGOTIATION-T")
String uriSegment = uri.substring(uri.lastIndexOf('/') + 1).toLowerCase();
if (uriSegment.equals(entry.getKey().toLowerCase())) {
    // ...
}
```

或者将 handler 的 `extensionKeyword()` 改为返回完整 URI,用 `equals` 匹配。

**核验要点**: 确认 A2A-T 规范中扩展 URI 的命名规则。如果 URI 路径段就是扩展名,上述方案可行。如果 URI 格式可能变化,需要更健壮的匹配逻辑。

---

### P2-4: `A2ATransport.buildClientCallContext` 手动调用 ExtensionInterceptor

**文件**: `A2ATransport.java:483-498`

**代码**:
```java
List<ClientCallInterceptor> interceptors = authManager.buildInterceptors(agentCard, agentName);
for (ClientCallInterceptor interceptor : interceptors) {
    if (interceptor instanceof ExtensionInterceptor extInterceptor) {
        try {
            ClientCallContext interceptCtx = new ClientCallContext(new HashMap<>(), headers);
            PayloadAndHeaders ph = extInterceptor.intercept(
                    "message/send", messageMetadata, headers, null, interceptCtx);
            headers.putAll(ph.getHeaders());
        } catch (Exception e) {
            log.warn("[Transport] Extension interceptor failed: {}", e.getMessage());
        }
    }
}
```

**WHY 需要关注**:

1. `buildInterceptors()` 返回 auth interceptor + extension interceptor 列表,但循环只调用 `ExtensionInterceptor`,跳过 `AuthInterceptor` / `CustomAuthInterceptor`。Auth 头由同方法中的 `applyAuthHeaders()` 独立设置。这意味着 `AuthInterceptor` 和 `CustomAuthInterceptor` 被构建但从未通过此路径调用——它们是事实上的死代码(对 `A2ATransport` 路径而言)。

2. `intercept()` 的第 4 个参数 `agentCard` 传 `null`。`ExtensionInterceptor.intercept()` 当前不使用 agentCard,所以功能正确,但语义上不应传 null。

3. 拦截器异常被 catch + warn 吞没,`A2A-Extensions` 头不会设置。如果扩展头缺失,Agent 可能不识别扩展语义。这个 fallback 行为是否合理取决于 Agent 的实现。

**HOW 优化方向**:

这不是 bug,但设计可以简化:
- 如果 SDK 的 `Client` 支持注册 `ClientCallInterceptor`(通过 `Client.builder().addInterceptor()`),应让 SDK 调用拦截器,而非手动调用
- 移除 `buildInterceptors()` 中返回 auth interceptor 的逻辑(因为 `applyAuthHeaders()` 已处理 auth),只返回 `ExtensionInterceptor`
- 或者将 `applyAuthHeaders()` 的逻辑移入 `CustomAuthInterceptor.intercept()`,统一通过拦截器链处理

**核验要点**: 确认 a2a-java SDK 的 `Client` 是否支持 `addInterceptor()`。如果支持,应迁移到 SDK 拦截器链。如果不支持,当前手动调用方式可接受,但应清理 auth interceptor 的冗余构建。

---

### P2-5: `DefaultControlPoint.onTask` 用空文本判断成功

**文件**: `DefaultControlPoint.java:66`

**代码**:
```java
boolean success = r.getText() != null && !r.getText().isEmpty();
```

**WHY 需要优化**:

`onTask` 的默认实现用"响应文本非空"判断任务是否成功。但:
- Agent 可能返回空文本但 Task 状态为 `TASK_STATE_COMPLETED`(例如只返回 artifact 不返回文本)
- Agent 可能返回非空文本但 Task 状态为 `TASK_STATE_FAILED`(例如错误消息)

更准确的判断应使用 Task 状态:
```java
String state = r.getTaskState();
boolean success = state != null && (state.contains("COMPLETED"));
```

**HOW 优化方向**:

```java
boolean success = r.getText() != null && !r.getText().isEmpty()
        && (r.getTaskState() == null || r.getTaskState().contains("COMPLETED"));
```

注意: 这是 `DefaultControlPoint` 的默认实现,工作台通常会重写 `onTask`。但如果使用默认实现,当前的判断逻辑可能产生误报。

**核验要点**: 确认是否有使用 `DefaultControlPoint` 而非自定义 `ControlPoint` 的场景。如果所有工作台都重写了 `onTask`,此问题不影响生产。

---

### P2-6: `ExtensionSender.sendNotification` 的 `eventCallback` 与 `sendExtensionMessage` 接口不一致

**文件**: `ExtensionSender.java:56-83`

**WHY 需要关注**:

`ExtensionSender` 接口有两个 Notification-T 便利方法:
- `sendNotification(agent, instruction, input, Consumer<Map<String, Object>> callback)` — 带回调
- `sendNotification(agent, instruction, input)` — 不带回调

default 实现中,带回调版本调用 `sendExtensionMessage(...)` — 不传回调。不带回调版本调用带回调版本,传 `null`。

`DefaultExtensionSender` 重写了带回调版本(line 122),正确处理回调。但接口 default 方法的设计导致了 P1-7 的问题(回调被丢弃)。

此外,`sendExtensionMessage` 是接口的核心方法,它不接受 `eventCallback` 参数。Notification-T 的回调语义只在 `DefaultExtensionSender` 的重写方法中存在,接口层没有强制。如果未来有其他实现,Notification-T 的回调行为不可保证。

**HOW 优化方向**:

将 `sendNotification(agent, instruction, input, callback)` 提升为接口方法(非 default),去掉 `sendExtensionMessage` 中对 Notification-T 的处理。让 Notification-T 有独立的接口契约:

```java
CompletableFuture<SendMessageResult> sendNotification(
        String agentName, String instruction, String naturalLanguageInput,
        Consumer<Map<String, Object>> eventCallback);
```

**核验要点**: 确认是否有调用方直接使用 `sendExtensionMessage` 发送 Notification-T。如果有,需要保持兼容。

---

## 附录: 已确认良好的设计

以下设计经检视确认合理,无需修改:

1. **`ConversationScopedA2AJavaClientRuntime` 接口**: 简洁的会话生命周期钩子,`closeConversation` 在 `DefaultWorkflowEngineClient.sendMessage` 的 `whenComplete` 中调用,确保协商循环结束后释放网关会话
2. **`A2ATExtension` 枚举**: 封装完整 URI,避免散落的字符串常量
3. **`ExtensionInterceptor`**: 只注入当前消息 metadata 中实际存在的扩展 URI,而非 AgentCard 上声明的所有扩展——正确行为
4. **`NegotiationTHandler.afterReceive`**: 使用最新 SDK 的无状态
   `validateProposePromptAndDataFilling` 校验提议内容并提取参数；缺少或非法
   `negotiationContext` 时失败关闭；执行引擎不调用旧状态机入口
   `receiveNegotiation`，也不保留绕过 SDK 的提取 fallback
5. **`DefaultWorkflowEngineClient.autoNegotiate`**: 递归协商循环有 `maxNegotiationRounds` 上限(默认 3),防止无限循环
6. **`SslContextFactory` mTLS 支持**: `loadKeyManagers` 支持客户端证书 + 私钥,RSA 密钥格式正确
7. **`CredentialCrypto` AES-GCM**: 使用 12 字节 IV + 128 位 tag,符合 NIST 推荐;IV 使用 `SecureRandom` 生成,无 IV 重用风险
8. **`ProtocolLogger`**: 独立 logger 名 "PROTOCOL",`INDENT_OUTPUT` 已启用,与引擎其他日志可独立控制级别
9. **`WorkflowExecutor.anySuccess`**: ANY_SUCCESS 语义正确——首个成功即返回,取消其余;全部失败才返回失败
10. **`EnvFileLoader`**: 不覆盖已存在的 OS 环境变量和系统属性,优先级正确

---

## 检视总结

| 优先级 | 编号 | 一句话描述 | 影响面 |
|--------|------|-----------|--------|
| P0-1 | 全局禁用主机名验证 | 安全:同 JVM 所有 HTTPS 失去主机名校验 | 生产环境安全风险 |
| P0-2 | 登录端点 trust-all SSL | 安全:凭证交换无 TLS 保护 | 凭证泄露风险 |
| P0-3 | Notification-T 超时吞没错误 | 正确性:真实失败被伪装为订阅成功 | 故障不可见 |
| P0-4 | SSE compact JSON 破坏值内空白 | 正确性:文本内容可能被篡改 | 语义传递错误 |
| P1-1 | 3 个扩展 prompt 生成器空桩 | 功能:SDK prompt 能力未利用 | 取决于 SDK 是否提供方法 |
| P1-2 | 无界 CachedThreadPool | 资源:线程数无上限 | 高并发内存风险 |
| P1-3 | 每次 send 新建 Client | 性能:连接不可复用 | 高吞吐性能退化 |
| P1-4 | AutoConfig 参数硬编码 | 可配置性:超时/线程池不可外部化 | 集成方无法调优 |
| P1-5 | A2AProperties 属性不全 | 可配置性:配置散落 @Value | 集成体验差 |
| P1-6 | 延迟步骤 50ms 轮询 | 性能:忙等待 | 深依赖链延迟累积 |
| P1-7 | sendNotification 默认方法丢回调 | 设计:接口默认行为错误 | 其他实现可能丢回调 |
| P2-1 | 解密失败返回密文 | 健壮性:错误根因被掩盖 | 运维排障困难 |
| P2-2 | setHttpClient 空操作 | 健壮性:误导性 API | 调用方误以为已传播 |
| P2-3 | contains 匹配扩展 URI | 健壮性:宽松匹配 | 未来扩展名冲突 |
| P2-4 | 手动调用拦截器 | 设计:auth interceptor 冗余 | 代码复杂度 |
| P2-5 | 空文本判断成功 | 健壮性:误报成功 | 默认 ControlPoint 误判 |
| P2-6 | sendNotification 接口不一致 | 设计:回调契约不强制 | 其他实现行为不可保证 |
