# SpringSpnDemo 完整调用链路

## 一、启动阶段

```
SpringSpnDemo.main()                                    // SpringSpnDemo.java:71
  ├── System.setProperty("disableHostnameVerification")
  ├── SpringWorkbenchApplication.loadDotEnv()           // 加载 .env → System properties
  └── new SpringSpnDemo().run(args)                     // SpringSpnDemo.java:75
```

## 二、run() 四阶段启动

```
run(args)                                               // SpringSpnDemo.java:82
  │
  ├── ① resolveTransportMode(args)                      // SpringSpnDemo.java:272
  │     优先级: --a2a.transport-mode= > System property > env var > 默认 "order"
  │
  ├── ② [mock模式] MockGatewayServer.start()            // SpringSpnDemo.java:97
  │     new MockGatewayServer("127.0.0.1", 26400,
  │         Set.of("https://127.0.0.1:26335", "https://127.0.0.1:26336"))
  │     → JDK HttpServer 监听 :26400，做 HTTP 反向代理
  │     → 校验 X-Target-URL 必须在 allowedTargets 白名单内
  │
  ├── ③ SpringApplication.run(SpringWorkbenchApplication) // SpringSpnDemo.java:108
  │     → Spring Boot 启动 HTTPS :26337 (Workbench Agent)
  │     → 自动装配:
  │         @Component SpringWorkbenchExecutor          // Agent 执行器
  │         @Component ClientRuntimeFactory             // 运行时工厂
  │         [simulator-enabled=true] EastcomOrderSimulatorServer :26401
  │
  ├── ④ startOmcAgents()                                // SpringSpnDemo.java:114
  │     ├── JdkHttpA2AServer(:26335, SpnDomainAgentCity1Executor)  // 粤东 OMC
  │     └── JdkHttpA2AServer(:26336, SpnDomainAgentCity2Executor)  // 粤西 OMC
  │
  └── ⑤ sendTaskToWorkbench(taskText)                   // SpringSpnDemo.java:130  发送 Task-T
```

## 三、北向调用：Demo → Workbench Agent

```
sendTaskToWorkbench(taskText)                           // SpringSpnDemo.java:218
  ├── 加载 transport_workbench_agent.json → AgentCard
  ├── new A2ATransport([wbCard], null, config)          // DIRECT 模式（Demo→Workbench 不走网关）
  ├── new DefaultWorkflowEngineClient(transport)
  └── client.sendMessage("Transport Workbench Agent", taskText).join()
        → HTTPS POST :26337/a2a/json/message:stream (SSE)
```

## 四、Workbench Agent 接收并编排

```
SpringWorkbenchExecutor.execute(ctx, emitter)           // SpringWorkbenchExecutor.java:80
  ├── extractText(ctx.getMessage())                     // 提取任务文本
  ├── ClientRuntimeFactory.create()                     // ★ 关键分叉点
  │     ├── mode=DIRECT  → return null                  // 用默认 A2A HTTP 直连
  │     ├── mode=MOCK    → new MockGatewayClientRuntime(mockGatewayUrl)
  │     └── mode=ORDER   → new OrderGatewayClientRuntime(orderConfig)
  │
  └── new WorkbenchOrchestrator(orchUrl, creds, ssl, env, clientRuntime).run(input)
```

## 五、WorkbenchOrchestrator.run() 编排流水线

```
WorkbenchOrchestrator.run(messageText)                  // WorkbenchOrchestrator.java:127
  │
  ├── STAGE 1: loadAgentCards()                         // WorkbenchOrchestrator.java:262
  │     从 classpath 读取 3 个 AgentCard JSON:
  │     ├── spn_domain_agent_city1.json
  │     ├── spn_domain_agent_city2.json
  │     └── transport_workbench_agent.json
  │
  ├── STAGE 2: searchPsop() + LoadPsop.load()           // WorkbenchOrchestrator.java:148
  │     → GET :5001/api/v1/orchestrate/search?intent=...
  │     → GET :5001/api/v1/orchestrate/psop/{psopId}
  │     → 返回 Workflow DAG 对象
  │
  ├── STAGE 3: new A2ATransport(cards, clientRuntime, config)  // WorkbenchOrchestrator.java:155
  │     ★ clientRuntime 就是 ClientRuntimeFactory.create() 的返回值
  │     ★ 这决定了后续所有南向调用走哪条路
  │
  ├── STAGE 4: ExtensionPrePositioner.prePosition()     // WorkbenchOrchestrator.java:172
  │     对每个非 Workbench Agent:
  │     ├── sender.sendAuthorization("下发授权放行策略")   // Authorization-T
  │     └── sender.sendNotification("订阅业务抢通结果通知") // Notification-T
  │
  └── STAGE 5: ExecutePsop.builder()...execute().join() // WorkbenchOrchestrator.java:195
        → WorkflowExecutor 执行 DAG 遍历
```

## 六、南向调用：三条路径（核心分叉）

```
A2ATransport.sendMessageAsync(agentName, message, ...)  // A2ATransport.java:286
  │
  ├── 如果 clientRuntime == null (DIRECT 模式):
  │     → a2aClientRuntime = DefaultA2AJavaClientRuntime
  │     → 直接 HTTPS POST 到 AgentCard 的 URL
  │     → 例: https://127.0.0.1:26335/a2a/json/message:send
  │
  ├── 如果 clientRuntime == MockGatewayClientRuntime (MOCK 模式):
  │     │
  │     └── OrderGatewayClientRuntime.sendMessage()     // 复用生产逻辑
  │           ├── routeResolver.resolve(agentCard)       // OrderGatewayClientRuntime.java:155
  │           │     → ConfiguredAgentGatewayRouteResolver
  │           │     → agentName → NE名 → AgentGatewayRoute(ne, interface)
  │           │
  │           ├── MockOrderHttpSessionClient.login()     // MockOrderHttpSessionClient.java:81
  │           ├── MockOrderHttpSessionClient.init(ne)    // MockOrderHttpSessionClient.java:91
  │           │
  │           ├── MockOrderHttpSessionClient.executeStreaming()  // MockOrderHttpSessionClient.java:130
  │           │     → HTTP POST http://127.0.0.1:26400{uriPath}
  │           │       Headers: X-Target-URL = targetUrl (OMC真实地址)
  │           │       Body: A2A SendMessageRequest JSON
  │           │
  │           │     MockGatewayServer 收到后:            // MockGatewayServer.java:108
  │           │     ├── 校验 X-Target-URL ∈ allowedTargets
  │           │     ├── 开 HTTPS 连接到 targetUrl
  │           │     └── 管道式转发 SSE 流 → 回传给客户端
  │           │
  │           └── GatewayA2AResponseParser.StreamingSession.accept()  // 解析事件
  │
  └── 如果 clientRuntime == OrderGatewayClientRuntime (ORDER 模式):
        │
        └── OrderGatewayClientRuntime.sendMessage()
              ├── routeResolver.resolve(agentCard)
              │     → agentName "Spn Domain Agent City1" → NE "yuedong-omc"
              │     → agentName "Spn Domain Agent City2" → NE "yuexi-omc"
              │
              ├── StreamingOrderHttpSessionClient.login()  // RSocket RPC 登录
              │     → service.login(Flux.just(loginRequest))
              │     → 东信指令平台验证 username/password/clientId/clientSecret
              │
              ├── StreamingOrderHttpSessionClient.init(ne, https)  // 绑定 NE
              │     → service.init(Flux.just(initRequest))
              │     → 指令平台建立 session → NE 映射
              │
              ├── StreamingOrderHttpSessionClient.executeStreaming()  // StreamingOrderHttpSessionClient.java:46
              │     → service.execute(Flux.just(wireRequest))
              │     → 东信指令平台根据 NE 路由，HTTP 转发到目标 OMC Agent
              │     → 返回 RSocket 流（SSE chunks）
              │     → .takeUntil(responseSink) 直到终态事件
              │
              ├── GatewayA2AResponseParser.StreamingSession.accept()
              │     → 逐块解析 SSE → protobuf StreamResponse → ClientEvent
              │
              └── StreamingOrderHttpSessionClient.logout()  // 关闭 session
```

## 七、事件解析链路

```
GatewayA2AResponseParser.StreamingSession               // GatewayA2AResponseParser.java:138
  │
  │  每个 SSE chunk 进来:
  ├── accept(chunk)                                     // GatewayA2AResponseParser.java:155
  │     ├── 检测 SSE 格式 (data:, event:, id:, retry:)
  │     ├── 追加到 frameBuffer
  │     ├── 找到 \n\n (完整 SSE frame 分隔符)
  │     └── drainFrames()                               // GatewayA2AResponseParser.java:202
  │           ├── 提取 data: 行的 JSON 内容
  │           ├── ProtoJsonUtils.merge(json, StreamResponse.newBuilder())
  │           │     → protobuf 反序列化为 StreamResponse
  │           ├── 判断事件类型:
  │           │     ├── hasMessage() → MessageEvent     // GatewayA2AResponseParser.java:225
  │           │     ├── hasTask()    → TaskEvent        // GatewayA2AResponseParser.java:238
  │           │     └── hasTaskUpdate() → TaskUpdateEvent // GatewayA2AResponseParser.java:251
  │           ├── 提取 taskState:
  │           │     COMPLETED / FAILED / CANCELED /
  │           │     REJECTED / INPUT_REQUIRED / AUTH_REQUIRED → terminal
  │           └── events.add(event) + 返回 isTerminal
  │
  │  流结束时:
  └── complete()                                        // GatewayA2AResponseParser.java:275
        └── flush 剩余 partial frame
```

## 八、DAG 执行：PSOP 遍历

```
ExecutePsop.execute()                                   // ExecutePsop.java:62
  └── WorkflowExecutor.execute()                        // WorkflowExecutor.java:128
        │
        ├── 找到入度=0 的步骤 → 并行执行
        │
        ├── STEP "diagnosis_city1" + "diagnosis_city2" (并行)
        │     └── WorkbenchControlPoint.onTask(step)    // WorkbenchControlPoint.java:67
        │           ├── 构建城市特定任务消息
        │           └── engineClient.sendMessage(agentName, taskMsg)
        │                 → A2ATransport → [网关路径] → OMC Agent
        │
        │     OMC Agent 侧 (SpnDomainAgentCity1Executor):
        │     ├── 首次: needsNegotiation()=true → INPUT_REQUIRED
        │     ├── Workbench 收到 → NegotiationStrategy 补充参数 → 重发
        │     ├── executeBusiness(): LLM 诊断
        │     │     City1: port-7 DOWN, -28dBm → 故障
        │     │     City2: 正常, -17dBm → 无故障
        │     └── selfTriggerRecovery(): 检查 Authorization-T 白名单 → 抢通
        │
        ├── STEP "merge_analysis" (依赖前两步)
        │     └── WorkbenchControlPoint.onSelfTask()    // WorkbenchControlPoint.java:121
        │           → LLM 合并两城诊断结果 → 定位故障城市
        │
        └── ExecutionResult → buildResultText() → 返回给 Demo
```

## 九、三种模式对比

```
┌─────────┬──────────────────────────────────────────────────────────┐
│  模式    │  南向调用路径                                              │
├─────────┼──────────────────────────────────────────────────────────┤
│ DIRECT  │ Workbench ──HTTPS──→ OMC Agent (直连)                     │
│         │ clientRuntime = null                                     │
├─────────┼──────────────────────────────────────────────────────────┤
│ MOCK    │ Workbench → OrderGatewayClientRuntime                     │
│         │   → MockOrderHttpSessionClient                            │
│         │     → HTTP POST MockGatewayServer(:26400)                 │
│         │       → X-Target-URL 校验 + HTTPS 反代 → OMC Agent        │
├─────────┼──────────────────────────────────────────────────────────┤
│ ORDER   │ Workbench → OrderGatewayClientRuntime                     │
│         │   → StreamingOrderHttpSessionClient                       │
│         │     → RSocket RPC → 东信指令平台                           │
│         │       → 平台根据 NE 路由 HTTP 转发 → OMC Agent             │
└─────────┴──────────────────────────────────────────────────────────┘
```

## 十、关键类文件索引

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| SpringSpnDemo | samples/.../spring/SpringSpnDemo.java | Demo 入口，启动各组件 |
| SpringWorkbenchApplication | samples/.../spring/SpringWorkbenchApplication.java | Spring Boot 启动类 |
| SpringWorkbenchExecutor | samples/.../spring/SpringWorkbenchExecutor.java | Workbench Agent 执行器 |
| ClientRuntimeFactory | samples/.../spring/ClientRuntimeFactory.java | 运行时工厂（DIRECT/MOCK/ORDER） |
| EastcomOrderSimulatorConfiguration | samples/.../spring/EastcomOrderSimulatorConfiguration.java | 模拟器 Spring 配置 |
| WorkbenchOrchestrator | samples/.../agents/WorkbenchOrchestrator.java | 编排流水线 |
| ExtensionPrePositioner | samples/.../agents/ExtensionPrePositioner.java | 扩展预置（Auth/Notif） |
| WorkbenchControlPoint | samples/.../agents/WorkbenchControlPoint.java | DAG 步骤分发 |
| NegotiationStrategy | samples/.../agents/NegotiationStrategy.java | 协商策略 |
| OrderGatewayClientRuntime | samples/.../OrderGatewayClientRuntime.java | 东信指令平台客户端 |
| MockGatewayClientRuntime | samples/.../MockGatewayClientRuntime.java | Mock 网关客户端 |
| StreamingOrderHttpSessionClient | samples/.../StreamingOrderHttpSessionClient.java | RSocket 流式会话 |
| MockOrderHttpSessionClient | samples/.../MockOrderHttpSessionClient.java | Mock HTTP 会话 |
| GatewayA2AResponseParser | samples/.../GatewayA2AResponseParser.java | SSE/protobuf 事件解析 |
| AgentGatewayRoute | samples/.../AgentGatewayRoute.java | 路由模型 |
| ConfiguredAgentGatewayRouteResolver | samples/.../ConfiguredAgentGatewayRouteResolver.java | 配置化路由解析 |
| MockGatewayServer | samples/.../server/MockGatewayServer.java | Mock 网关 HTTP 反代 |
| EastcomOrderSimulatorServer | samples/.../server/EastcomOrderSimulatorServer.java | 东信 RSocket 模拟器 |
| JdkHttpA2AServer | samples/.../server/JdkHttpA2AServer.java | OMC Agent HTTPS 服务器 |
| A2ATransport | workflow-engine/.../client/A2ATransport.java | A2A 传输层 |
| WorkflowExecutor | workflow-engine/.../core/WorkflowExecutor.java | DAG 执行引擎 |
| ExecutePsop | workflow-engine/.../runner/ExecutePsop.java | PSOP 运行器 |
| LoadPsop | workflow-engine/.../registry/LoadPsop.java | PSOP 加载/搜索 |
