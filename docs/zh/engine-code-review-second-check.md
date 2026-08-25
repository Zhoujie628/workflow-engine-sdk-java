# 引擎核心代码检视报告——二次审核意见

> **归档状态（2026-08-25）**：本文只记录旧 commit 的审核过程。已采纳项的当前实现与验证结果见
> [CHANGELOG](../../CHANGELOG.md)、[API 参考](API_REFERENCE.md) 和 [架构设计](DESIGN.md)；下文不是当前接口契约。

> **审核对象**: `docs/zh/engine-code-review.md`  
> **审核范围**: `workflow-engine`、`spring-boot-starter`，并交叉核验同目录下的 `a2a-java`、`a2a-t-sdk-java`  
> **审核日期**: 2026-08-18  
> **代码基线**: `dev` 分支 `39118ca`  
> **说明**: 本文是对原检视报告的二次审核，不替代正式安全测试、压力测试和端到端联调结论。

---

## 1. 总体结论

原检视报告方向总体合理，但不能原样作为整改清单使用，主要存在以下问题：

1. 部分问题定级过高，例如 SSE JSON 压缩被误判为会修改字符串语义。
2. 部分修复方案本身不可用，例如使用无界 `LinkedBlockingQueue` 构造所谓“有界线程池”。
3. 部分问题需要结合实际依赖实现重新解释，例如 A2A SDK 的 consumption timeout 并不是 SSE 相邻事件间隔超时。
4. P1-7 与 P2-6 是同一个接口契约问题，存在重复计数。
5. 报告遗漏了自定义 CA/mTLS 未接入默认运行链路、运行时线程池未关闭、协议日志泄露认证信息等重要问题。

建议修正后形成：

- P0：2 项；
- P1：6 项左右；
- P2：5 项左右；
- 删除重复项 P2-6；
- 将原 P0-4 降为普通性能/可读性清理。

---

## 2. 原报告逐项审核

### 2.1 P0-1：`sslVerify=false` 全局禁用 JVM 主机名验证

**审核结论：问题成立，保留 P0；原修复建议需要修正。**

证据：

- `DefaultA2AJavaClientRuntime` 构造函数设置 JVM 系统属性：
  `System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true")`。
- `SslContextFactory.createTrustAll()` 再次设置相同属性。
- `DefaultA2AJavaClientRuntime()` 无参构造函数默认使用 `sslVerify=false`。

该系统属性影响同一 JVM 中的 JDK HTTP Client，而不是只影响当前 A2A Client。对于作为依赖库运行在 Spring Boot 工作台中的场景，这会扩大到其他出站 HTTPS 调用。

原报告提出“仅使用 trust-all SSLContext、不关闭主机名验证即可满足开发环境”的表述不准确。信任证书链和主机名校验是两层不同的验证：trust-all 只跳过证书链可信性判断，并不会自动允许证书 SAN/CN 与目标主机不一致。

**建议整改：**

1. 默认构造函数改为安全默认值 `sslVerify=true`。
2. 通用库不得在运行时修改 JVM 全局 TLS 属性。
3. 生产环境使用受信 CA 或显式配置的自定义 CA，并保证证书 SAN 与访问地址一致。
4. 如确需开发环境不安全模式，应通过隔离的传输实现提供，并打印明确安全告警，不能污染整个 JVM。

---

### 2.2 P0-2：`AgentCredentialService` 登录端点无条件 trust-all

**审核结论：问题成立，保留 P0。**

当调用方没有注入 `HttpClient` 时，`AgentCredentialService` 总是使用 `SslContextFactory.createTrustAll()`。标准 `AgentAuthManager` 创建服务时也没有传入自定义客户端，因此默认凭证登录路径实际无法受工作流客户端的 `sslVerify`、自定义 CA 等配置控制。

这意味着即使 A2A 消息通道开启了严格 TLS 校验，获取登录 Token 的用户名、密码交换仍可能不验证服务端身份。

**建议整改：**

1. 将 TLS/HttpClient 作为显式依赖注入 `AgentCredentialService`。
2. Token 登录默认严格校验证书和主机名。
3. 登录链路与消息链路共享统一 TLS 配置模型，但允许分别覆盖。
4. 自签证书通过 CA 信任配置解决，不能以 trust-all 作为生产方案。

---

### 2.3 P0-3：Notification-T 超时处理吞没真实错误

**审核结论：问题成立，建议调整为 P1 高优先级。**

当前实现：

```java
return future.orTimeout(5, TimeUnit.SECONDS)
        .exceptionally(e -> subscribedWithoutAck());
```

`exceptionally` 会处理所有异常。流在 5 秒内发生连接失败、HTTP 错误、协议解析失败时，也会被转换为 `TASK_STATE_WORKING`，导致调用方把真实失败识别成订阅成功。

该问题只影响 Notification-T 订阅能力，因此是否定为 P0 取决于该能力是否属于发布阻断项；从通用引擎定级看，P1 高优先级更准确。

**建议整改：**

1. 解包 `CompletionException`/`ExecutionException` 后，仅对真实 `TimeoutException` 采用“暂未收到 ACK”的兼容行为。
2. 其他错误必须异常完成，不得转换成成功结果。
3. 返回初始订阅状态后，后台 SSE 发生异常仍要通过状态回调、健康事件或重连管理器通知订阅生命周期持有者。
4. 5 秒等待值应支持配置。

---

### 2.4 P0-4：SSE compact JSON 会破坏字符串内空白

**审核结论：核心判断不成立，应从 P0 删除。**

`lines().map(String::trim)` 删除的是 pretty-printed JSON 每个物理行两端的缩进和换行。对于：

```json
{
  "text": " Authorization-T pre-positioned "
}
```

`trim()` 作用于包含整个属性的物理行，只会删除 `{`、属性缩进和 `}` 周围的结构空白，不会删除双引号内部的前后空格。合法 JSON 字符串中的换行也必须转义，不能直接跨物理行，因此原报告示例不能证明语义被篡改。

以下两个次要意见可以保留：

- `reduce("", String::concat)` 会产生不必要的中间字符串，较大报文下可能出现 O(n²) 拷贝；
- 可以优先使用 protobuf printer 的紧凑输出能力，减少手工处理。

`\r\n` 与 `\n` 都是 SSE 允许的行结束形式，平台差异不是协议错误。

**修正定级：P2 性能/实现简化。**

---

### 2.5 P1-1：三个扩展 Prompt 生成器为空桩

**审核结论：不属于当前功能缺陷，应归入能力路线图或低优先级清理。**

交叉核验当前 `a2a-t-sdk-java` 后，`A2ATClient` 公开的 Prompt 生成接口只有：

```java
generateTaskPrompt(Object userInput)
```

不存在 `generateNegotiationPrompt`、`generateAuthorizationPrompt`、`generateNotificationPrompt`。因此原报告所称“已有 SDK 能力未利用”不成立。

当前 `DefaultExtensionSender` 类注释也明确说明另外三种能力为“reserved for future SDK support”，运行时会回退使用原始自然语言输入。

可以进行的维护性优化：

- 删除三个没有实际行为的方法，由分派逻辑直接回退；或
- 保留方法并添加明确 TODO、SDK 版本和预期契约。

在 A2A-T SDK 尚未提供对应接口前，不建议定为 P1。

---

### 2.6 P1-2：`A2ATransport` 使用无界 CachedThreadPool

**审核结论：问题成立，保留 P1；原修复代码不正确。**

`A2ATransport.asyncExecutor` 和 `DefaultA2AJavaClientRuntime.httpClientExecutor` 都使用 `newCachedThreadPool`。高并发工作流和阻塞式发送可能造成线程数量快速增长。

原报告建议：

```java
new ThreadPoolExecutor(4, 16, ..., new LinkedBlockingQueue<>(), ...)
```

这里的 `LinkedBlockingQueue<>` 没有容量上限。任务进入无界队列后，线程池通常只保留 corePoolSize 个线程，`maximumPoolSize=16` 和 `CallerRunsPolicy` 基本不会发挥预期作用。

**建议整改：**

- 使用有容量的 `ArrayBlockingQueue` 或 `new LinkedBlockingQueue<>(capacity)`；
- 配置 core、max、queue capacity、keep-alive 和拒绝策略；
- 将 Executor 作为可注入依赖，明确所有权和关闭责任；
- 长生命周期 Notification-T 订阅与短请求发送使用不同的资源模型。

---

### 2.7 P1-3：每次 send 创建新的 Client/HttpClient

**审核结论：问题成立，保留 P1。**

当前默认运行时每次消息发送都会创建 `Client` 和 `A2AHttpClient`，结束后关闭 Client。这样无法充分复用连接池、TLS 会话和底层传输资源，协商多轮发送场景会重复建立连接。

交叉核验当前 `a2a-java` 源码，其 `Client` Javadoc 已明确声明实例是线程安全的，可以并发使用。因此原报告中的线程安全待确认项已有答案：可以设计缓存，但事件 Consumer 必须保持线程安全。

**建议整改：**

- 优先按 Agent 接口 URL、协议绑定、TLS 配置缓存 Client/Transport；
- AgentCard 或接口配置变化时更新缓存；
- 在 runtime `close()` 中统一关闭缓存资源；
- 增加并发发送、AgentCard 更新和关闭时竞态测试。

---

### 2.8 P1-4：AutoConfiguration 超时和线程池参数硬编码

**审核结论：可配置性问题成立，保留 P1；部分原因解释错误。**

`a2a.blocking.agent.timeout.seconds`、`a2a.blocking.consumption.timeout.seconds` 和固定 8 线程都应该支持 Spring Boot 外部配置。

但原报告把 `consumption timeout=5s` 解释为“SSE 两个事件之间最多间隔 5 秒”并不符合上游 `a2a-java` 定义。上游定义是阻塞调用中等待事件消费和持久化完成的超时，不是 SSE idle timeout。

**建议整改：**

- 在通用 starter 的 `A2AProperties` 中增加服务端 blocking timeout、executor core/max/queue capacity 等通用配置；
- 保留当前默认值以兼容上游 SDK；
- 不要依据错误的 SSE 间隔解释直接把默认值扩大到 60～120 秒；
- 增加配置绑定测试。

---

### 2.9 P1-5：`A2AProperties` 缺少工作台/网关配置

**审核结论：原整改方向不合理。**

`spring-boot-starter` 当前承担的是通用 A2A 服务端接入职责，`A2AProperties` 使用 `a2at.server` 前缀。把以下内容加入其中会造成通用模块依赖具体应用和厂商适配：

- `a2a.order.host`；
- `a2a.order.port`；
- 东信模拟器配置；
- 工作台 sample 的 credentials/env 路径。

这违背依赖倒置和模块边界，且使主干通用 starter 被东信场景污染。

**建议整改：**

- 通用服务端参数继续放在 `A2AProperties`；
- 通用出站引擎参数如确需 Spring 自动装配，可新建 `WorkflowEngineClientProperties`；
- 东信参数放在 samples 或独立 `eastcom-order-adapter-starter` 的 `OrderGatewayProperties`；
- 用专用 `@ConfigurationProperties` 替换 sample 中散落的 `@Value`。

因此，“配置散落需要治理”可以保留，但“全部加入 A2AProperties”应否决。

---

### 2.10 P1-6：WorkflowExecutor 使用 50ms sleep 轮询

**审核结论：原性能分析不准确，但代码存在更严重的正确性问题，应重写该条。**

正常执行路径会等待当前所有 ready step 的 `CompletableFuture.allOf()` 完成，再进入下一轮 DAG 遍历。因此正常的十层依赖链不会每层额外进入一次 50ms sleep。

`readySteps` 为空且仍有 deferred step，通常意味着：

- 依赖缺失；
- 路由产生不可达节点；
- 依赖图存在环；
- 前置步骤未产生预期 output。

当前实现在超过 `workflow.getSteps().size()` 次后，仅把步骤加入 `executed` 并跳过，没有把工作流设置为失败。最后 `ExecutionResult.success` 仍可能为 true。这才是需要优先解决的问题。

**建议整改：**

1. 当 pending 非空但没有 ready step 且没有正在执行的 step 时，立即识别为依赖死锁。
2. 输出未满足的前置步骤列表。
3. 将工作流标记为失败，不能静默跳过。
4. 在工作流加载/启动阶段增加缺失依赖和环检测。
5. 删除 50ms 轮询逻辑，或只保留在确实存在异步外部依赖的明确场景中。

---

### 2.11 P1-7：`ExtensionSender.sendNotification` 默认实现丢弃回调

**审核结论：问题成立，但建议降为 P2，并与 P2-6 合并。**

接口默认方法接受 `eventCallback`，却直接调用不带 callback 的 `sendExtensionMessage()`。当前唯一实现 `DefaultExtensionSender` 已覆盖该方法，所以现有主路径没有丢回调，但未来实现类可能静默违反接口契约。

**建议整改：**

- 将带 callback 的 `sendNotification` 改为非 default 抽象方法；
- 不带 callback 的便利方法可以继续默认传 null；
- 增加接口契约测试。

---

### 2.12 P2-1：解密失败返回密文

**审核结论：问题成立，建议提升为 P1。**

`CredentialCrypto.decryptIfNeeded()` 在以下情况都会返回原始 `enc:...` 字符串：

- 缺少 `A2AT_CRED_KEY`；
- 密文格式不正确；
- Base64、密钥长度或 GCM 认证失败。

随后系统会把密文当成密码继续发起登录，最终表现为远端 401 或认证失败，掩盖真实配置错误。

**建议整改：**

- 带 `enc:` 前缀的数据一旦无法解密必须抛出明确异常；
- 异常信息包含配置位置和失败类型，但不得包含密文、密钥或明文；
- 应用启动阶段预校验加密凭证，避免到第一次调用时才失败。

---

### 2.13 P2-2：`AgentAuthManager.setHttpClient()` 是空操作

**审核结论：问题成立，保留 P2。**

该方法没有修改任何服务，日志却输出“HTTP client propagated”，属于误导性 API 和不正确日志。仓库中也没有实际调用点。

如果采用依赖注入式认证客户端设计，应通过构造函数/Factory 创建服务；在此之前建议直接删除该方法和错误日志，而不是保留一个看似可用的空实现。

---

### 2.14 P2-3：扩展 URI 使用 `contains()` 匹配

**审核结论：问题成立，保留 P2；原修复方案不可直接使用。**

`contains()` 可能把 `Task-T-Extended` 错误匹配为 `Task-T`。

原报告建议取 URI 最后一个路径段，但当前测试和常用 URI 是：

```text
https://a2a.example.org/extensions/Task-T/v1
```

最后一个路径段是 `v1`，不是 `Task-T`，因此该建议会破坏现有匹配。

**建议整改：**

- 使用 `URI` 解析路径；
- 对路径段进行精确、大小写不敏感匹配；
- 明确定义扩展名和版本段规则；
- 或由 handler 声明完整 URI/受支持版本集合；
- 增加 `Task-T-Extended`、非法 URI、query/fragment 等测试。

---

### 2.15 P2-4：手动调用 ExtensionInterceptor

**审核结论：设计问题成立，保留 P2；整改要兼容自定义 Runtime。**

`buildInterceptors()` 同时创建认证和扩展 interceptor，但 `A2ATransport` 只手动执行 `ExtensionInterceptor`。认证头由 `applyAuthHeaders()` 另外生成，因此 auth interceptor 在该路径上属于冗余对象。

不能简单把逻辑全部迁移到 a2a-java Client interceptor，因为 `A2ATransport` 同时服务于默认直连 Runtime 和东信 Order Gateway Runtime，自定义 Runtime 也需要拿到最终 Header。

**建议整改：**

- 抽出与具体 SDK 无关的 `HeaderContributor` 或 `ClientCallContextFactory`；
- 认证、扩展分别实现 Header 贡献逻辑；
- Runtime 只消费构建完成的 `ClientCallContext`；
- required 扩展构建失败时应 fail-fast，optional 扩展才允许降级；
- 清理当前未执行的 auth interceptor 构建逻辑。

---

### 2.16 P2-5：用空文本判断任务成功

**审核结论：问题成立，建议提升为 P1。**

当前判断：

```java
boolean success = r.getText() != null && !r.getText().isEmpty();
```

会导致：

- Task 已 COMPLETED，但只返回 artifact 时被判断为失败；
- Task 已 FAILED，但错误消息非空时被判断为成功。

原报告提出“文本非空且状态 COMPLETED”仍会错误拒绝 artifact-only 的成功响应。

**建议规则：**

1. 有明确状态时，以状态为准；
2. `COMPLETED` 为成功；
3. `FAILED`、`CANCELED`、`REJECTED` 为失败；
4. `INPUT_REQUIRED`、`AUTH_REQUIRED` 不应作为普通任务成功；
5. 只有旧协议或 Message-only 响应没有状态时，才回退到文本/内容判断；
6. 后续可增加 artifact 到 `TaskResponse` 的映射。

---

### 2.17 P2-6：Notification callback 接口不一致

**审核结论：删除，和 P1-7 重复。**

两条描述的是同一个事实：默认接口方法可能静默丢弃 callback。应合并为一个 P2 接口契约问题，避免重复统计和重复整改。

---

## 3. 原报告遗漏的问题

### 3.1 P1：`caCertsPath` 和 mTLS 没有接入默认运行链路

这是原报告最重要的遗漏之一。

`DefaultA2AJavaClientRuntime` 虽然接收、保存并打印 `caCertsPath`，但 `createHttpClient()` 的实际逻辑是：

- `sslVerify=true`：直接创建默认 `JdkA2AHttpClient`；
- `sslVerify=false`：创建 trust-all JDK HttpClient。

代码没有调用 `SslContextFactory.create(verifyServer, caCertsPath, ...)`。全仓库也没有找到该 `create()` 方法的实际调用点。

因此：

- `caCertsPath` 当前不生效；
- `SslContextFactory` 中的 mTLS 代码没有被默认 Runtime 使用；
- 附录中“mTLS 支持已确认良好”只能说明辅助代码存在，不能说明引擎能力已经可用；
- gRPC 路径也明确只使用默认信任库，自定义 CA 需要自定义 Runtime。

**建议整改：**统一构建 HTTP/JSONRPC/gRPC 的 TLS 配置，补充自定义 CA、证书错误、mTLS 和关闭验证的集成测试，并对 TLS 配置错误 fail-fast。

---

### 3.2 P1：`DefaultA2AJavaClientRuntime.close()` 没有关闭线程池

`httpClientExecutor` 是 Runtime 持有的 ExecutorService，但 `close()` 只打印日志，没有执行 shutdown。`A2ATransport.close()` 只会关闭自己的 `asyncExecutor`，无法替代 Runtime 对内部资源的关闭责任。

**建议整改：**

- Runtime 自己创建的 Executor 必须在 `close()` 中关闭；
- 外部注入的 Executor 不应由 Runtime 擅自关闭；
- 增加重复 close、正在发送时 close、关闭后拒绝新请求等测试。

---

### 3.3 P1/P2：PROTOCOL 日志明文输出敏感 Header 和 Body

`ProtocolLogger` 会输出完整 Header，其中可能包括：

- `Authorization: Bearer ...`；
- Cookie、API Key、自定义认证头；
- 任务正文、协商信息及业务数据。

独立 logger 便于联调是合理设计，但生产配置和开源默认实践需要同时考虑敏感信息保护。

**建议整改：**

- 默认脱敏 Authorization、Cookie、Proxy-Authorization 和已知 API Key Header；
- 提供显式的敏感 Header 开关，仅在受控联调环境启用；
- 启用时输出安全告警；
- 文档说明日志文件权限、保留周期和问题单脱敏要求；
- Body 如需完整打印，也应有独立开关和长度限制。

---

### 3.4 P2：form-urlencoded 登录参数没有编码

`AgentCredentialService` 当前直接拼接：

```java
key=value&key2=value2
```

如果用户名或密码包含 `+`、`&`、`=`、`%`、空格或中文，请求语义会改变。

**建议整改：**按 UTF-8 对 key 和 value 分别执行 form URL encoding，并增加特殊字符测试。

---

### 3.5 P2：AuthProvider 与 credentials 的同名 Header 冲突策略不清晰

当前顺序为：

1. 自定义 `AuthProvider` 写入 Header；
2. credentials 认证随后写入 Header。

如果两者都写 `Authorization`，后执行的 credentials 会覆盖前者。部分集成文档却表述为两者“互不覆盖”，与 Map 的实际行为不一致。

**建议整改：**

- 推荐两种认证方式二选一；或
- 检测同名 Header 并 fail-fast；或
- 明确并测试覆盖优先级，同时修正文档。

---

### 3.6 P2：TLS 配置错误存在潜在 fail-open 语义

`SslContextFactory.create()` 在加载 CA/证书发生异常时记录：

```text
Falling back to no verification
```

随后返回 `Optional.empty()`。虽然当前默认 Runtime 没有实际调用该方法，但从公共方法契约看，生产 TLS 配置错误不应该被解释为关闭验证。

**建议整改：**证书、私钥或 CA 配置存在但加载失败时直接抛出配置异常。只有调用方显式设置不安全模式时，才允许关闭验证。

---

## 4. 对原报告“已确认良好设计”附录的修正

以下结论基本可以保留：

1. `ConversationScopedA2AJavaClientRuntime` 的会话生命周期抽象；
2. `A2ATExtension` 集中维护扩展 URI；
3. `ExtensionInterceptor` 只注入当前消息实际携带的扩展；
4. Negotiation-T SDK 处理与直接提取的双路径；
5. 协商轮次上限；
6. `WorkflowExecutor.anySuccess` 的基本语义；
7. `EnvFileLoader` 不覆盖更高优先级配置。

以下结论需要改写：

### 4.1 mTLS

`SslContextFactory.loadKeyManagers()` 的实现可以作为正面基础，但默认 Runtime 没有接入，不能直接得出“引擎已经支持 mTLS”的结论。还需验证 PKCS#8、证书链、加密私钥、证书轮换、非 RSA 密钥等边界。

### 4.2 CredentialCrypto

AES-GCM、12 字节随机 IV 和 128 位 Tag 的算法选择合理。`SecureRandom` 能把 IV 碰撞概率降到极低，但“无 IV 重用风险”是过度绝对的表达。更重要的是，解密失败返回密文使整体凭证处理不满足 fail-safe。

### 4.3 ProtocolLogger

独立 logger 和 pretty JSON 有利于协议联调，应保留。但“无需修改”不成立：必须补充敏感信息控制、默认脱敏和日志治理说明。

---

## 5. 建议整改优先级

### 第一批：安全和结果正确性

1. 移除 JVM 全局关闭主机名验证的行为，调整默认构造器安全值。
2. 修复凭证登录 trust-all，并统一 TLS 配置。
3. 修复 Notification-T 将真实失败伪装成订阅成功的问题。
4. 修复 `CredentialCrypto` 解密失败继续使用密文的问题。
5. 修复 `DefaultControlPoint` 的成功状态判断。
6. 修复 WorkflowExecutor 对未满足依赖静默跳过并可能报告成功的问题。

### 第二批：资源和生产可配置性

1. 使用可配置的有界 Executor，并实现背压。
2. 缓存可安全复用的 A2A Client/Transport。
3. 正确关闭 Runtime 内部线程池和缓存资源。
4. 外部化 starter 的通用 timeout、pool 和 queue 参数。
5. 真正接入自定义 CA 和 mTLS。

### 第三批：接口和维护性

1. 收紧 Notification callback 接口契约。
2. 清理 `AgentAuthManager.setHttpClient()` 空操作。
3. 重构 Header 构建和 interceptor 抽象。
4. 修复扩展 URI 的精确匹配。
5. 修复 form-urlencoded 编码。
6. 明确 AuthProvider 与 credentials 冲突策略。
7. 优化 SSE JSON 紧凑序列化，但不再作为 P0 正确性问题。

---

## 6. 建议第三方重点复核的问题

为了避免二次审核本身产生新的误判，建议后续检视人员重点验证：

1. JDK 当前目标版本下，是否存在不修改 JVM 全局属性且能对单个 `HttpClient` 关闭主机名验证的受支持方案。
2. `a2a-java` 当前实际依赖版本是否与同目录源码一致，以及 Client/Transport 缓存和关闭的线程安全边界。
3. Notification-T 在 5 秒无 ACK 后，底层 Future 和 SSE 流是否仍保持独立生命周期，以及异常如何上报。
4. 工作流模型中未选择的条件分支是否可能被登记为其他节点的静态 predecessor，避免把合法路由误判为依赖死锁。
5. A2A-T 扩展 URI 的规范化格式、版本段规则和大小写要求。
6. PROTOCOL 完整报文日志在正式交付环境中的安全要求，是否允许通过显式开关打印 Authorization。

以上核验项完成后，再冻结最终整改清单和优先级。
