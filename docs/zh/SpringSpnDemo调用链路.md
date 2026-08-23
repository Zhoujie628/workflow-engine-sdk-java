# SpringSpnDemo 完整调用链路

## 一、启动阶段

```mermaid
graph TD
    A["SpringSpnDemo.main()<br/>// SpringSpnDemo.java:71"]
    A --> B["System.setProperty('disableHostnameVerification')"]
    A --> C["SpringWorkbenchApplication.loadDotEnv()<br/>// 加载 .env → System properties"]
    A --> D["new SpringSpnDemo().run(args)<br/>// SpringSpnDemo.java:75"]
```

## 二、run() 四阶段启动

```mermaid
graph TD
    A["run(args)<br/>// SpringSpnDemo.java:82"]
    A --> B["① resolveTransportMode(args)<br/>// SpringSpnDemo.java:272<br/>优先级: --a2a.transport-mode= &gt; System property &gt; env var &gt; 默认 'order'"]
    A --> C["② [mock模式] MockGatewayServer.start()<br/>// SpringSpnDemo.java:97<br/>new MockGatewayServer('127.0.0.1', 26400,<br/>Set.of('https://127.0.0.1:26335', 'https://127.0.0.1:26336'))<br/>→ JDK HttpServer 监听 :26400，做 HTTP 反向代理<br/>→ 校验 X-Target-URL 必须在 allowedTargets 白名单内"]
    A --> D["③ SpringApplication.run(SpringWorkbenchApplication)<br/>// SpringSpnDemo.java:108<br/>→ Spring Boot 启动 HTTPS :26337 (Workbench Agent)<br/>→ 自动装配:<br/>@Component SpringWorkbenchExecutor // Agent 执行器<br/>@Component ClientRuntimeFactory // 运行时工厂<br/>[simulator-enabled=true] EastcomOrderSimulatorServer :26401"]
    A --> E["④ startOmcAgents()<br/>// SpringSpnDemo.java:114"]
    E --> F["JdkHttpA2AServer(:26335, SpnDomainAgentCity1Executor)<br/>// 城市1 OMC"]
    E --> G["JdkHttpA2AServer(:26336, SpnDomainAgentCity2Executor)<br/>// 城市2 OMC"]
    A --> H["⑤ sendTaskToWorkbench(taskText)<br/>// SpringSpnDemo.java:130 发送 Task-T"]
```

## 三、北向调用：Demo → Workbench Agent

```mermaid
graph TD
    A["sendTaskToWorkbench(taskText)<br/>// SpringSpnDemo.java:218"]
    A --> B["加载 transport_workbench_agent.json → AgentCard"]
    A --> C["new A2ATransport([wbCard], null, config)<br/>// DIRECT 模式（Demo→Workbench 不走网关）"]
    A --> D["new DefaultWorkflowEngineClient(transport)"]
    A --> E["client.sendMessage('Transport Workbench Agent', taskText).join()<br/>→ HTTPS POST :26337/a2a/json/message:stream (SSE)"]
```

## 四、Workbench Agent 接收并编排

```mermaid
graph TD
    A["SpringWorkbenchExecutor.execute(ctx, emitter)<br/>// SpringWorkbenchExecutor.java:90"]
    A --> B["extractText(ctx.getMessage())<br/>// 提取任务文本"]
    A --> C["ClientRuntimeFactory.create()<br/>// ★ 关键分叉点"]
    C --> D["mode=DIRECT → return null<br/>// 用默认 A2A HTTP 直连"]
    C --> E["mode=MOCK → new MockGatewayClientRuntime(mockGatewayUrl)"]
    C --> F["mode=ORDER → new OrderGatewayClientRuntime(orderConfig)"]
    A --> G["new WorkbenchOrchestrator(orchUrl, creds, ssl, env, clientRuntime).run(input)"]
```

## 五、WorkbenchOrchestrator.run() 编排流水线

```mermaid
graph TD
    A["WorkbenchOrchestrator.run(messageText)<br/>// WorkbenchOrchestrator.java:127"]
    A --> B["STAGE 1: loadAgentCards()<br/>// WorkbenchOrchestrator.java:262<br/>从 classpath 读取 3 个 AgentCard JSON:"]
    B --> B1["spn_domain_agent_city1.json"]
    B --> B2["spn_domain_agent_city2.json"]
    B --> B3["transport_workbench_agent.json"]
    A --> C["STAGE 2: searchPsop() + LoadPsop.load()<br/>// WorkbenchOrchestrator.java:148<br/>→ GET :5001/api/v1/orchestrate/search?intent=...<br/>→ GET :5001/api/v1/orchestrate/psop/{psopId}<br/>→ 返回 Workflow DAG 对象"]
    A --> D["STAGE 3: new A2ATransport(cards, clientRuntime, config)<br/>// WorkbenchOrchestrator.java:155<br/>★ clientRuntime 就是 ClientRuntimeFactory.create() 的返回值<br/>★ 这决定了后续所有南向调用走哪条路"]
    A --> E["STAGE 4: ExtensionPrePositioner.prePosition()<br/>// WorkbenchOrchestrator.java:172<br/>对每个非 Workbench Agent:"]
    E --> E1["sender.sendAuthorization('下发授权放行策略')<br/>// Authorization-T"]
    E --> E2["sender.sendNotification('订阅业务抢通结果通知')<br/>// Notification-T"]
    A --> F["STAGE 5: ExecutePsop.builder()...execute().join()<br/>// WorkbenchOrchestrator.java:195<br/>→ WorkflowExecutor 执行 DAG 遍历"]
```

## 六、南向调用：三条路径（核心分叉）

```mermaid
graph TD
    A["A2ATransport.sendMessageAsync(agentName, message, ...)<br/>// A2ATransport.java:286"]
    A --> D["如果 clientRuntime == null (DIRECT 模式)<br/>→ a2aClientRuntime = DefaultA2AJavaClientRuntime<br/>→ 直接 HTTPS POST 到 AgentCard 的 URL<br/>→ 例: https://127.0.0.1:26335/a2a/json/message:send"]
    A --> M["如果 clientRuntime == MockGatewayClientRuntime (MOCK 模式)"]
    M --> M1["OrderGatewayClientRuntime.sendMessage()<br/>// 复用生产逻辑"]
    M1 --> M2["routeResolver.resolve(agentCard)<br/>// OrderGatewayClientRuntime.java:155<br/>→ ConfiguredAgentGatewayRouteResolver<br/>→ agentName → NE名 → AgentGatewayRoute(ne, interface)"]
    M1 --> M3["MockOrderHttpSessionClient.login()<br/>// MockOrderHttpSessionClient.java:81"]
    M1 --> M4["MockOrderHttpSessionClient.init(ne)<br/>// MockOrderHttpSessionClient.java:91"]
    M1 --> M5["MockOrderHttpSessionClient.executeStreaming()<br/>// MockOrderHttpSessionClient.java:130<br/>→ HTTP POST http://127.0.0.1:26400{uriPath}<br/>Headers: X-Target-URL = targetUrl (OMC真实地址)<br/>Body: A2A SendMessageRequest JSON"]
    M5 --> M6["MockGatewayServer 收到后<br/>// MockGatewayServer.java:108"]
    M6 --> M7["校验 X-Target-URL ∈ allowedTargets"]
    M6 --> M8["开 HTTPS 连接到 targetUrl"]
    M6 --> M9["管道式转发 SSE 流 → 回传给客户端"]
    M1 --> M10["GatewayA2AResponseParser.StreamingSession.accept()<br/>// 解析事件"]
    A --> O["如果 clientRuntime == OrderGatewayClientRuntime (ORDER 模式)"]
    O --> O1["OrderGatewayClientRuntime.sendMessage()"]
    O1 --> O2["routeResolver.resolve(agentCard)<br/>→ agentName 'Spn Domain Agent City1' → NE 'city1-omc'<br/>→ agentName 'Spn Domain Agent City2' → NE 'city2-omc'"]
    O1 --> O3["StreamingOrderHttpSessionClient.login()<br/>// RSocket RPC 登录<br/>→ service.login(Flux.just(loginRequest))<br/>→ 指令平台验证 username/password/clientId/clientSecret"]
    O1 --> O4["StreamingOrderHttpSessionClient.init(ne, https)<br/>// 绑定 NE<br/>→ service.init(Flux.just(initRequest))<br/>→ 指令平台建立 session → NE 映射"]
    O1 --> O5["StreamingOrderHttpSessionClient.executeStreaming()<br/>// StreamingOrderHttpSessionClient.java:46<br/>→ service.execute(Flux.just(wireRequest))<br/>→ 指令平台根据 NE 路由，HTTP 转发到目标 OMC Agent<br/>→ 返回 RSocket 流（SSE chunks）<br/>→ .takeUntil(responseSink) 直到终态事件"]
    O1 --> O6["GatewayA2AResponseParser.StreamingSession.accept()<br/>→ 逐块解析 SSE → protobuf StreamResponse → ClientEvent"]
    O1 --> O7["StreamingOrderHttpSessionClient.logout()<br/>// 关闭 session"]
```

## 七、事件解析链路

```mermaid
graph TD
    A["GatewayA2AResponseParser.StreamingSession<br/>// GatewayA2AResponseParser.java:138<br/>每个 SSE chunk 进来:"]
    A --> B["accept(chunk)<br/>// GatewayA2AResponseParser.java:155"]
    B --> B1["检测 SSE 格式 (data:, event:, id:, retry:)"]
    B --> B2["追加到 frameBuffer"]
    B --> B3["找到 \n\n (完整 SSE frame 分隔符)"]
    B --> B4["drainFrames()<br/>// GatewayA2AResponseParser.java:202"]
    B4 --> B5["提取 data: 行的 JSON 内容"]
    B4 --> B6["ProtoJsonUtils.merge(json, StreamResponse.newBuilder())<br/>→ protobuf 反序列化为 StreamResponse"]
    B4 --> B7["判断事件类型:"]
    B7 --> B8["hasMessage() → MessageEvent<br/>// GatewayA2AResponseParser.java:225"]
    B7 --> B9["hasTask() → TaskEvent<br/>// GatewayA2AResponseParser.java:238"]
    B7 --> B10["hasTaskUpdate() → TaskUpdateEvent<br/>// GatewayA2AResponseParser.java:251"]
    B4 --> B11["提取 taskState:<br/>COMPLETED / FAILED / CANCELED /<br/>REJECTED / INPUT_REQUIRED / AUTH_REQUIRED → terminal"]
    B4 --> B12["events.add(event) + 返回 isTerminal"]
    A --> C["流结束时:"]
    C --> D["complete()<br/>// GatewayA2AResponseParser.java:275"]
    D --> E["flush 剩余 partial frame"]
```

## 八、DAG 执行：PSOP 遍历

```mermaid
graph TD
    A["ExecutePsop.execute()<br/>// ExecutePsop.java:62"]
    A --> B["WorkflowExecutor.execute()<br/>// WorkflowExecutor.java:128"]
    B --> C["找到入度=0 的步骤 → 并行执行"]
    B --> D["STEP 'diagnosis_city1' + 'diagnosis_city2' (并行)"]
    D --> E["WorkbenchControlPoint.onTask(step)<br/>// WorkbenchControlPoint.java:67"]
    E --> E1["构建城市特定任务消息"]
    E --> E2["engineClient.sendMessage(agentName, taskMsg)<br/>→ A2ATransport → [网关路径] → OMC Agent"]
    E --> F["OMC Agent 侧 (SpnDomainAgentCity1Executor):"]
    F --> F1["首次: needsNegotiation()=true → INPUT_REQUIRED"]
    F --> F2["Workbench 收到 → NegotiationStrategy 补充参数 → 重发"]
    F --> F3["executeBusiness(): LLM 诊断<br/>City1: port-7 DOWN, -28dBm → 故障<br/>City2: 正常, -17dBm → 无故障"]
    F --> F4["selfTriggerRecovery(): 检查 Authorization-T 白名单 → 抢通"]
    B --> G["STEP 'merge_analysis' (依赖前两步)"]
    G --> H["WorkbenchControlPoint.onSelfTask()<br/>// WorkbenchControlPoint.java:121<br/>→ LLM 合并两城诊断结果 → 定位故障城市"]
    B --> I["ExecutionResult → buildResultText() → 返回给 Demo"]
```

## 九、三种模式对比

| 模式 | 南向调用路径 |
|------|-------------|
| DIRECT | Workbench ──HTTPS──→ OMC Agent (直连)<br>clientRuntime = null |
| MOCK | Workbench → OrderGatewayClientRuntime<br>→ MockOrderHttpSessionClient<br>→ HTTP POST MockGatewayServer(:26400)<br>→ X-Target-URL 校验 + HTTPS 反代 → OMC Agent |
| ORDER | Workbench → OrderGatewayClientRuntime<br>→ StreamingOrderHttpSessionClient<br>→ RSocket RPC → 指令平台<br>→ 平台根据 NE 路由 HTTP 转发 → OMC Agent |

## 十、关键类文件索引

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| SpringSpnDemo | samples/.../demo/SpringSpnDemo.java | Demo 入口，启动各组件 |
| SpringWorkbenchApplication | samples/.../SpringWorkbenchApplication.java | Spring Boot 启动类 |
| SpringWorkbenchExecutor | samples/.../workbench/SpringWorkbenchExecutor.java | Workbench Agent 执行器 |
| ClientRuntimeFactory | samples/.../gateway/ClientRuntimeFactory.java | 运行时工厂（DIRECT/MOCK/ORDER） |
| EastcomOrderSimulatorConfiguration | samples/.../gateway/EastcomOrderSimulatorConfiguration.java | 模拟器 Spring 配置 |
| WorkbenchOrchestrator | samples/.../workbench/WorkbenchOrchestrator.java | 编排流水线 |
| ExtensionPrePositioner | samples/.../extension/ExtensionPrePositioner.java | 扩展预置（Auth/Notif） |
| WorkbenchControlPoint | samples/.../workbench/WorkbenchControlPoint.java | DAG 步骤分发 |
| NegotiationStrategy | samples/.../negotiation/NegotiationStrategy.java | 协商策略 |
| OrderGatewayClientRuntime | samples/.../gateway/OrderGatewayClientRuntime.java | 指令平台客户端 |
| MockGatewayClientRuntime | samples/.../gateway/MockGatewayClientRuntime.java | Mock 网关客户端 |
| StreamingOrderHttpSessionClient | samples/.../gateway/StreamingOrderHttpSessionClient.java | RSocket 流式会话 |
| MockOrderHttpSessionClient | samples/.../gateway/MockOrderHttpSessionClient.java | Mock HTTP 会话 |
| GatewayA2AResponseParser | samples/.../gateway/GatewayA2AResponseParser.java | SSE/protobuf 事件解析 |
| AgentGatewayRoute | samples/.../gateway/AgentGatewayRoute.java | 路由模型 |
| ConfiguredAgentGatewayRouteResolver | samples/.../gateway/ConfiguredAgentGatewayRouteResolver.java | 配置化路由解析 |
| MockGatewayServer | samples/.../gateway/MockGatewayServer.java | Mock 网关 HTTP 反代 |
| EastcomOrderSimulatorServer | samples/.../gateway/EastcomOrderSimulatorServer.java | RSocket 模拟器 |
| JdkHttpA2AServer | samples/.../server/JdkHttpA2AServer.java | OMC Agent HTTPS 服务器 |
| A2ATransport | workflow-engine/.../client/A2ATransport.java | A2A 传输层 |
| WorkflowExecutor | workflow-engine/.../core/WorkflowExecutor.java | DAG 执行引擎 |
| ExecutePsop | workflow-engine/.../runner/ExecutePsop.java | PSOP 运行器 |
| LoadPsop | workflow-engine/.../registry/LoadPsop.java | PSOP 加载/搜索 |
