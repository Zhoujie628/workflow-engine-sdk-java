# 引擎代码检视——二次审核回应与最终共识

> **回应对象**: `docs/zh/engine-code-review-second-check.md`
> **回应日期**: 2026-08-18
> **回应人**: 原检视方

---

## 1. 总体回应

二次审核质量很高，纠正了原报告 5 处实质性错误：

1. **P0-4 SSE JSON**：我对 `trim()` 的行为判断错误，接受降级
2. **P1-1 扩展 prompt**：我对 SDK 能力的描述不准确，接受降级
3. **P1-2 线程池修复代码**：`LinkedBlockingQueue` 无界导致 `maximumPoolSize` 不生效，接受修正
4. **P1-4 consumption timeout**：我把 SDK 参数含义解释错了，接受修正
5. **P1-5 A2AProperties**：把东信配置放入通用 starter 违反模块边界，接受否决
6. **P1-6 50ms 轮询**：我把正常路径的性能影响当成了主要问题，实际真正问题是静默跳过未标记失败
7. **P2-3 URI 匹配修复**：取最后路径段得到 `v1` 而非 `Task-T`，接受修正
8. **P2-5 成功判断修复**：`text 非空 AND COMPLETED` 会拒绝 artifact-only 响应，接受修正

同时确认二次审核新发现的 6 个遗漏问题全部成立。

以下逐条回应。

---

## 2. 逐条回应

### 2.1 P0-1：全局禁用主机名验证 → 共识 P0

**审核纠正**: "仅使用 trust-all SSLContext、不关闭主机名验证即可满足开发环境"的表述不准确——trust-all 只跳过证书链可信性，不自动允许 SAN/CN 不匹配。

**回应**: 接受纠正。trust-all `TrustManager` 的 `checkServerTrusted` 不检查证书链，但 JDK `HttpClient` 的主机名验证是独立于 `TrustManager` 的另一层，由 `jdk.internal.httpclient.disableHostnameVerification` 控制。两层验证混淆了。

**最终共识**:
1. 保留 P0
2. 默认构造函数 `DefaultA2AJavaClientRuntime()` 的 `sslVerify=false` 改为 `true`
3. 通用库不得在运行时修改 JVM 全局 TLS 属性
4. 开发环境如需不安全模式，通过隔离的传输实现提供，打印安全告警

---

### 2.2 P0-2：AgentCredentialService trust-all → 共识 P0

**回应**: 无异议。保留 P0。

最终整改方向：
1. 将 TLS/HttpClient 作为显式依赖注入 `AgentCredentialService`
2. Token 登录默认严格校验证书和主机名
3. 自签证书通过 CA 信任配置解决

---

### 2.3 P0-3：Notification-T 超时吞没错误 → 共识 P1 高

**审核纠正**: 建议从 P0 降为 P1 高优先级，因为只影响 Notification-T 订阅能力，不是发布阻断项。

**回应**: 接受降级。Notification-T 不在核心工作流执行路径上，它只影响工作台的恢复通知订阅。P1 高优先级合理。

**最终共识**:
1. 降为 P1 高
2. `exceptionally` 中解包 `CompletionException` 后，仅对 `TimeoutException` 采用"暂未收到 ACK"兼容行为
3. 其他错误必须异常完成
4. 5 秒等待值支持配置

---

### 2.4 P0-4：SSE compact JSON → 共识：降为 P2

**审核纠正**: `lines().map(String::trim)` 只删除每行两端的缩进，不会删除 JSON 字符串值内的空白。JSON 字符串中的换行必须转义（`\n`），不能跨物理行。

**回应**: 接受。我重新验证了：

```
pretty-printed JSON:
{
  "text": " value "
}

lines() → ["{", "  \"text\": \" value \"", "}"]
trim()  → ["{", "\"text\": \" value \"", "}"]
concat  → {"text": " value "}
```

`trim()` 作用于物理行，只去缩进，值内空白在引号内保留。原报告的"破坏值内空白"判断不成立。

**最终共识**:
1. 降为 P2（性能/实现简化）
2. `reduce("", String::concat)` 的 O(n²) 拷贝是次要问题
3. 可优先使用 protobuf printer 的紧凑输出能力
4. `\r\n` vs `\n` 是平台差异，SSE 规范允许两者

---

### 2.5 P1-1：三个扩展 prompt 生成器 → 共识：降为低优先级/路线图

**审核纠正**: 交叉核验 `a2a-t-sdk-java` 后，`A2ATClient` 只公开 `generateTaskPrompt(Object userInput)`。不存在其他三个 generate 方法。原报告"SDK 能力未利用"不成立。

**回应**: 接受。我未核实 SDK 实际 API，臆测了方法存在。当前实现不是"未利用 SDK 能力"，而是"SDK 尚未提供对应能力"。

**最终共识**:
1. 降为低优先级/路线图项
2. 可删除三个空桩方法，由分派逻辑直接回退
3. 或保留方法并添加 TODO + 预期 SDK 契约
4. 在 SDK 提供对应方法前不定为 P1

---

### 2.6 P1-2：无界 CachedThreadPool → 共识 P1（修复代码需修正）

**审核纠正**: 原报告的 `new LinkedBlockingQueue<>()` 无容量上限，任务进入无界队列后线程池通常只保留 corePoolSize 个线程，`maximumPoolSize` 和 `CallerRunsPolicy` 基本不生效。

**回应**: 接受。这是 `ThreadPoolExecutor` 的已知行为——`LinkedBlockingQueue` 无界时，`maximumPoolSize` 永远不会触发。我的修复代码是错误的。

**最终共识**:
1. 保留 P1
2. 使用有容量的 `ArrayBlockingQueue` 或 `new LinkedBlockingQueue<>(capacity)`
3. 配置 core、max、queue capacity、keep-alive 和拒绝策略
4. 将 Executor 作为可注入依赖，明确所有权和关闭责任
5. 长生命周期 Notification-T 订阅与短请求发送使用不同资源模型

---

### 2.7 P1-3：每次 send 创建新 Client → 共识 P1

**审核补充**: a2a-java `Client` Javadoc 声明实例线程安全，可以并发使用。缓存可行。

**回应**: 接受补充。SDK 线程安全确认后，缓存方案可行。原报告的"确认 SDK 是否线程安全"待办项已有答案。

**最终共识**:
1. 保留 P1
2. 按 Agent 接口 URL、协议绑定、TLS 配置缓存 Client/Transport
3. AgentCard 或接口配置变化时更新缓存
4. `close()` 中统一关闭缓存资源
5. 增加并发发送、AgentCard 更新和关闭时竞态测试

---

### 2.8 P1-4：AutoConfiguration 参数硬编码 → 共识 P1（原因解释修正）

**审核纠正**: `consumption timeout=5s` 不是"SSE 相邻事件间隔超时"，而是阻塞调用中等待事件消费和持久化完成的超时。

**回应**: 接受。我未核实 SDK 定义，按字面推测了含义。

**最终共识**:
1. 保留 P1
2. 在 `A2AProperties` 中增加服务端 blocking timeout、executor core/max/queue capacity 配置
3. 保留当前默认值以兼容上游 SDK
4. 不依据错误的 SSE 间隔解释扩大默认值
5. 增加配置绑定测试

---

### 2.9 P1-5：A2AProperties 缺少工作台/网关配置 → 共识：原方向否决，治理保留

**审核纠正**: 把 `a2a.order.host` 等东信特有配置放入通用 `spring-boot-starter` 的 `A2AProperties` 会造成通用模块依赖具体厂商适配，违背依赖倒置和模块边界。

**回应**: 完全接受。我的方案会污染通用 starter。

**最终共识**:
1. "配置散落需要治理"保留，但"全部加入 A2AProperties"否决
2. 通用服务端参数放 `A2AProperties`
3. 通用出站引擎参数放新建 `WorkflowEngineClientProperties`
4. 东信参数放 samples 或独立 `OrderGatewayProperties`
5. 用专用 `@ConfigurationProperties` 替换 sample 中散落的 `@Value`

---

### 2.10 P1-6：50ms sleep 轮询 → 共识：重写为正确性问题

**审核纠正**: 正常执行路径会等待 `allOf()` 完成，十层依赖链不会每层额外 50ms。真正的问题是：步骤被 deferred 超过 `workflow.getSteps().size()` 次后静默加入 `executed` 并跳过，但工作流仍可能报告 `success=true`。

**回应**: 接受。我重新审视代码：

```java
int dc = deferCount.getOrDefault(idx, 0) + 1;
if (dc > workflow.getSteps().size()) {
    log.warn("Step {} deferred too many times, skipping", step.getName());
    executed.add(idx);  // 标记为已执行
    // 但没有设置 failed[0] = true!
}
```

跳过的步骤不会产生 `stepOutputs`，依赖它的后续步骤也会被跳过。但 `failed[0]` 没有被设置，最终 `ExecutionResult.success` 仍为 `true`。这是一个正确性 bug。

**最终共识**:
1. 重写为正确性问题，保留 P1
2. pending 非空但没有 ready step 且没有正在执行的 step 时，识别为依赖死锁
3. 输出未满足的前置步骤列表
4. 将工作流标记为失败
5. 工作流加载阶段增加缺失依赖和环检测
6. 删除 50ms 轮询逻辑

---

### 2.11 P1-7 / P2-6：sendNotification 默认方法丢回调 → 共识：合并为 P2

**回应**: 接受合并。P1-7 和 P2-6 描述的是同一问题。

**最终共识**:
1. 合并为一个 P2
2. 将带 callback 的 `sendNotification` 改为非 default 抽象方法
3. 不带 callback 的便利方法继续 default 传 null
4. 增加接口契约测试

---

### 2.12 P2-1：解密失败返回密文 → 共识：提升为 P1

**审核纠正**: 建议提升为 P1。

**回应**: 接受。带 `enc:` 前缀的数据无法解密时返回密文，会导致后续认证以密文作为密码，远端返回 401 掩盖根因。这是 fail-open 行为，生产环境不应允许。

**最终共识**:
1. 提升为 P1
2. `enc:` 前缀数据无法解密时抛出明确异常
3. 异常信息包含配置位置和失败类型，不含密文/密钥/明文
4. 应用启动阶段预校验加密凭证

---

### 2.13 P2-2：setHttpClient 空操作 → 共识 P2

**回应**: 无异议。保留 P2。直接删除方法和误导性日志。

---

### 2.14 P2-3：contains() 匹配 → 共识 P2（修复方案修正）

**审核纠正**: 原修复建议取 URI 最后一个路径段，但 `https://.../Task-T/v1` 的最后路径段是 `v1`，不是 `Task-T`。

**回应**: 接受。我的修复方案确实会破坏现有匹配。

**最终共识**:
1. 保留 P2
2. 使用 `URI` 解析路径
3. 对路径段进行精确、大小写不敏感匹配
4. 明确定义扩展名和版本段规则
5. 增加 `Task-T-Extended`、非法 URI、query/fragment 测试

---

### 2.15 P2-4：手动调用 ExtensionInterceptor → 共识 P2

**审核补充**: 不能简单迁移到 a2a-java Client interceptor，因为 `A2ATransport` 同时服务直连 Runtime 和东信 Gateway Runtime，自定义 Runtime 也需要拿到最终 Header。

**回应**: 接受。迁移到 SDK interceptor 链会绕过自定义 Runtime 的 Header 消费。

**最终共识**:
1. 保留 P2
2. 抽出与具体 SDK 无关的 `HeaderContributor` 或 `ClientCallContextFactory`
3. 认证、扩展分别实现 Header 贡献逻辑
4. Runtime 只消费构建完成的 `ClientCallContext`
5. required 扩展构建失败时 fail-fast，optional 允许降级
6. 清理当前未执行的 auth interceptor 构建逻辑

---

### 2.16 P2-5：空文本判断成功 → 共识：提升为 P1

**审核纠正**: 原修复建议 `text 非空 AND state COMPLETED` 会错误拒绝 artifact-only 的成功响应。

**回应**: 接受。Task COMPLETED 但只返回 artifact 不返回文本时，`r.getText()` 为空，我的条件会判失败。

**最终共识**:
1. 提升为 P1
2. 有明确状态时以状态为准：COMPLETED 为成功，FAILED/CANCELED/REJECTED 为失败
3. INPUT_REQUIRED/AUTH_REQUIRED 不作为普通任务成功
4. 只有无状态时才回退到文本/内容判断
5. 后续可增加 artifact 到 `TaskResponse` 的映射

---

## 3. 遗漏问题回应

### 3.1 caCertsPath 和 mTLS 未接入默认运行链路 → 接受，P1

**回应**: 这是原报告最重要的遗漏。`DefaultA2AJavaClientRuntime.createHttpClient()` 确实没有调用 `SslContextFactory.create(verifyServer, caCertsPath, ...)`。`caCertsPath` 被接收、保存、打印但从未使用。mTLS 代码是死代码。

原报告附录"mTLS 支持已确认良好"是过度结论——只验证了辅助代码存在，没有验证引擎能力可用。

**最终共识**: P1。统一构建 HTTP/JSONRPC/gRPC 的 TLS 配置，补充自定义 CA、mTLS 集成测试，TLS 配置错误 fail-fast。

### 3.2 DefaultA2AJavaClientRuntime.close() 不关闭线程池 → 接受，P1

**回应**: `httpClientExecutor` 是 Runtime 持有的 `ExecutorService`，`close()` 只打印日志不 shutdown。资源泄漏确认。

**最终共识**: P1。Runtime 自建 Executor 必须在 `close()` 中关闭。增加重复 close、发送中 close、关闭后拒绝新请求测试。

### 3.3 PROTOCOL 日志明文输出敏感 Header → 接受，P1/P2

**回应**: `ProtocolLogger` 输出完整 Header 包括 `Authorization: Bearer ...`。独立 logger 便于联调是合理设计，但生产环境需要脱敏。`A2ATransport` 中已有 `WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS` 环境变量控制，但只作用于 `OrderGatewayClientRuntime` 路径，引擎核心的 `ProtocolLogger` 没有脱敏逻辑。

**最终共识**: P1。默认脱敏 Authorization/Cookie/Proxy-Authorization 和已知 API Key Header，提供显式开关，启用时输出安全告警。

### 3.4 form-urlencoded 未编码 → 接受，P2

**回应**: `AgentCredentialService.login()` 中 `form.append(e.getKey()).append("=").append(e.getValue())` 确实没有 URL encode。包含 `&`、`=`、`+`、`%`、空格、中文的值会破坏请求语义。

**最终共识**: P2。按 UTF-8 对 key 和 value 分别执行 form URL encoding，增加特殊字符测试。

### 3.5 AuthProvider 与 credentials 同名 Header 冲突 → 接受，P2

**回应**: `buildClientCallContext()` 中 `authProvider.applyAuth()` 先写 Header，`applyAuthHeaders()` 后写，后者覆盖前者。部分集成文档说"互不覆盖"与 Map 实际行为不一致。

**最终共识**: P2。推荐二选一，或检测同名 Header fail-fast，或明确并测试覆盖优先级，修正文档。

### 3.6 SslContextFactory.create() fail-open → 接受，P2

**回应**: `SslContextFactory.create()` 在加载 CA/证书异常时 `return Optional.empty()`，日志 "Falling back to no verification"。虽然当前默认 Runtime 没有调用此方法，但公共方法契约不应 fail-open。

**最终共识**: P2。配置存在但加载失败时抛出异常，只有调用方显式设置不安全模式时才允许关闭验证。

---

## 4. 附录修正

### mTLS

**原结论**: "SslContextFactory.loadKeyManagers() 的实现可以作为正面基础，但默认 Runtime 没有接入，不能直接得出'引擎已经支持 mTLS'的结论。"

**回应**: 接受。原报告附录第 6 条"SslContextFactory mTLS 支持：loadKeyManagers 支持客户端证书+私钥，RSA 密钥格式正确"应改写为：辅助代码存在但未接入运行链路，引擎 mTLS 能力不可用。

### CredentialCrypto

**原结论**: "'无 IV 重用风险'是过度绝对的表达。解密失败返回密文使整体凭证处理不满足 fail-safe。"

**回应**: 接受。AES-GCM 算法选择正确，`SecureRandom` 生成的 12 字节 IV 碰撞概率极低但非零。解密失败返回密文的 fail-open 行为是更严重的问题（已提升为 P1）。附录结论应改写为：算法选择合理，但 fail-open 行为使整体凭证处理不满足 fail-safe。

### ProtocolLogger

**原结论**: "'无需修改'不成立：必须补充敏感信息控制、默认脱敏和日志治理说明。"

**回应**: 接受。原报告附录第 8 条"ProtocolLogger：独立 logger 名，INDENT_OUTPUT 已启用，与引擎其他日志可独立控制级别"应补充："但未脱敏敏感 Header，生产环境需补充"。

---

## 5. 最终整改优先级共识

### 第一批：安全和结果正确性（P0/P1 高）

| # | 问题 | 优先级 | 整改要点 |
|---|------|--------|---------|
| 1 | JVM 全局禁用主机名验证 | P0 | 移除全局属性修改，默认 `sslVerify=true` |
| 2 | AgentCredentialService trust-all | P0 | TLS/HttpClient 显式注入，登录默认严格校验 |
| 3 | Notification-T 超时吞没真实错误 | P1 高 | 仅 TimeoutException 走兼容路径，其他异常完成 |
| 4 | CredentialCrypto 解密失败返回密文 | P1 | `enc:` 前缀无法解密时抛异常，启动预校验 |
| 5 | DefaultControlPoint 空文本判断成功 | P1 | 以 Task 状态为准，无状态才回退文本判断 |
| 6 | WorkflowExecutor 静默跳过未满足依赖 | P1 | 依赖死锁时标记失败，加载阶段环检测 |
| 7 | caCertsPath/mTLS 未接入默认运行链路 | P1 | 统一 TLS 配置构建，补充 mTLS 集成测试 |
| 8 | Runtime.close() 不关闭线程池 | P1 | 自建 Executor 在 close() 中 shutdown |
| 9 | PROTOCOL 日志明文输出敏感 Header | P1 | 默认脱敏，显式开关 |

### 第二批：资源和生产可配置性（P1）

| # | 问题 | 整改要点 |
|---|------|---------|
| 10 | 无界 CachedThreadPool | 有界 `ArrayBlockingQueue`，可配置，Executor 注入 |
| 11 | 每次 send 新建 Client | 按 URL+协议+TLS 缓存 Client，close() 统一关闭 |
| 12 | AutoConfig 参数硬编码 | A2AProperties 增加 timeout/pool/queue 配置 |
| 13 | 配置散落 @Value | 分层 Properties：通用/引擎/厂商各自独立 |

### 第三批：接口和维护性（P2）

| # | 问题 | 整改要点 |
|---|------|---------|
| 14 | sendNotification 接口丢回调 | 带回调版本改为抽象方法 |
| 15 | setHttpClient 空操作 | 删除方法和误导日志 |
| 16 | Header 构建/interceptor 抽象 | 抽出 HeaderContributor，清理冗余 auth interceptor |
| 17 | contains() 匹配扩展 URI | URI 解析+路径段精确匹配 |
| 18 | form-urlencoded 未编码 | UTF-8 form URL encode |
| 19 | AuthProvider/credentials Header 冲突 | 二选一或 fail-fast 或明确优先级 |
| 20 | SslContextFactory.create() fail-open | 配置加载失败抛异常 |
| 21 | SSE compact JSON | 改用 protobuf printer 紧凑输出，消除 O(n²) |

---

## 6. 待第三方重点复核项

同意二次审核第 6 节的 6 个复核项，补充以下 2 项：

7. **JDK HttpClient 主机名验证**: 确认 JDK 11-21 是否有 per-client 的主机名验证 API（非全局系统属性）
8. **a2a-java Client 生命周期**: 确认 `Client.close()` 是否关闭底层连接池，缓存的 Client 在 AgentCard 更新时是否需要重建

---

## 7. 总结

二次审核纠正了原报告 8 处实质错误，补充了 6 个遗漏问题。最终共识：

- **P0**: 2 项（全局主机名验证、登录 trust-all）
- **P1 高**: 7 项（Notification-T 超时、CredentialCrypto fail-open、成功判断、静默跳过依赖、mTLS 未接入、线程池泄漏、协议日志脱敏）
- **P1**: 4 项（无界线程池、Client 缓存、参数硬编码、配置散落）
- **P2**: 8 项（接口契约、空操作、Header 抽象、URI 匹配、form 编码、Header 冲突、fail-open、SSE JSON）

原报告删除重复项 P2-6，P0-4 降为 P2，P1-1 降为路线图项。
