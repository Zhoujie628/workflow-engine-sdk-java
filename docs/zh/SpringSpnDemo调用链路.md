# SpringSpnDemo 当前调用链路

> 校准日期：2026-08-25。以当前源码和锁定的 A2A-T SDK commit
> `0ef79d37f49a9b7a2dbe16b6d9fd1ccdb6d9538d` 为准。

## 1. 业务结果

WAIMO 通过 Task-T 向传输工作台下发投诉诊断任务。工作台使用 A2A-T SDK 校验并提取
诊断参数，搜索/加载 PSOP，向两个地市 OMC 并行下发诊断任务。两路都完成后，
join 节点只执行一次汇总，工作台把真实汇总文本放入 Task-T artifact 返回 WAIMO。

Authorization-T 和 Notification-T 不是上述 DAG 的步骤。Spring 工作台生命周期在独立时机
预置白名单并建立抢通结果订阅。

## 2. 启动顺序

```mermaid
sequenceDiagram
    participant Demo as SpringSpnDemo
    participant OMC1 as City1 OMC
    participant OMC2 as City2 OMC
    participant WB as Spring Workbench
    participant Ext as WorkbenchExtensionLifecycle

    Demo->>OMC1: 启动 HTTPS/SSE Agent (:26335)
    Demo->>OMC2: 启动 HTTPS/SSE Agent (:26336)
    Demo->>WB: SpringApplication.run (:26337)
    WB->>Ext: ApplicationReadyEvent
    Ext->>OMC1: Authorization-T（独立一次通道）
    Ext->>OMC2: Authorization-T（独立一次通道）
    Ext->>OMC1: Notification-T（独立长连接通道）
    Ext->>OMC2: Notification-T（独立长连接通道）
```

`SpringWorkbenchExtensionLifecycle` 为 Authorization-T 和 Notification-T 分别调用
`ClientRuntimeFactory.create()`，并拒绝两者复用同一 runtime 实例。Authorization transport 在全部
ACK 成功后关闭；Notification transport 保留到订阅完成、取消或 Spring 容器关闭。

## 3. 北向 WAIMO 请求

`SpringSpnDemo.sendTaskToWorkbench()` 创建专用北向 transport，调用
`sendMessageFromData` 与 SDK `StandardTemplates.PRIVATE_LINE_COMPLAINT`：

1. SDK schema-aware 管线将原始投诉数据渲染为 Task-T prompt。
2. A2A Java SDK 通过 `https://127.0.0.1:26337/a2a/json/message:stream` 发送。
3. Spring starter 的 `A2AController` 交给 `SpringWorkbenchExecutor`。
4. `WorkbenchTaskInputParser` 根据 metadata 中的规范 Task-T URI 和 `templateUri` 调用
   A2A-T server validate-and-fill；缺少 SDK 配置或校验失败时显式失败。

schema-aware `fromData` 跳过场景识别，但 slot 映射仍可使用 SDK 配置的 LLM。

## 4. 工作流执行

```mermaid
flowchart LR
    W[WAIMO Task-T] --> P[SDK 校验与输入提取]
    P --> L[搜索/加载 PSOP]
    L --> C1[City1 诊断]
    L --> C2[City2 诊断]
    C1 --> J[等待两个活跃前驱]
    C2 --> J
    J --> M[汇总分析，仅一次]
    M --> R[Task-T artifact 返回 WAIMO]
```

`WorkflowExecutor` 在调度前校验节点、边和环，只等待本次执行已激活的前驱。并行分支的
结果按稳定顺序合并，join 使用原子状态保证 exact-once。`WorkbenchControlPoint.onSelfTask`
对两个 OMC 返回做本地汇总；`WorkbenchOrchestrator.buildResultText` 优先取
`merge_analysis`/`merge` 输出，不用假的“成功”文本代替业务结果。

示例首先从编排中心搜索并加载 PSOP。若本地编排中心不可用，或其开发证书不满足 Java
主机名校验，示例会明确记录 `PSOP_FALLBACK` 并装载等价的三节点内存工作流；不会再拿到
fallback 标识后继续调用失败的远端加载接口。生产集成应配置含正确 SAN 的证书并使用远端
PSOP，不应把示例 fallback 当作编排中心的容灾实现。

## 5. 南向传输分支

`ClientRuntimeFactory` 支持：

| 模式 | runtime | 用途 |
|---|---|---|
| `direct` | `null` → `DefaultA2AJavaClientRuntime` | 工作台直接访问 OMC AgentCard URL |
| `order` | `OrderGatewayClientRuntime` | 通过东信 Order SDK/指令平台选择地市 NE 并转发到 OMC |
| `mock` | `MockGatewayClientRuntime` | 仅用于本地 HTTP 适配测试 |

默认为 `order`。三种运行时仅改变线路，不改变 A2A/A2A-T metadata、任务状态、
Negotiation-T 轮次或业务回调契约。Order 模式的每个 agent/context 会话独立，两地市并行
诊断不得共享可变 NE 路由状态；Negotiation-T follow-up 复用同一对话。

`EASTCOM_ORDER_SIMULATOR_ENABLED=true` 只在 `order` 模式启动本地东信协议模拟器；
`direct` 模式不会占用模拟器端口，也不会意外经过东信 SDK。

## 6. Negotiation-T

OMC 仅在 Task-T 缺少必填参数或存在业务语义错误时发起协商。LLM/配置/网络故障会
直接失败，不伪装成参数协商。OMC 用 typed propose 渲染 SDK prompt，工作台校验
`templateUri` 和 `negotiationContext={id,round,maxRounds}`，再用 typed accept/reject/abort 回复。
引擎不使用已从 SDK 删除的旧状态机接口，不存在原始文本 fallback。

主投诉工作流给两个地市都下发完整诊断输入，因此正常主链路不会人为制造缺参来触发
Negotiation-T；协商能力由独立协议用例覆盖。

## 7. Authorization-T 与 Notification-T

`ExtensionPrePositioner` 对每个 OMC：

1. 用 `sendExtensionMessageFromData` 渲染并发送白名单；ACK 必须为
   `TASK_STATE_COMPLETED`，否则启动失败。
2. 用 `openNotificationFromData` 建立长连接；ACK 必须为 `WORKING` 或 `COMPLETED`。
3. OMC 诊断给出抢通方案后，只在 SDK 已验证的白名单精确命中业务场景/处置类型/
   操作名称/有效期时自动执行。没有白名单时 fail-closed。
4. 执行结果通过 Notification-T `recovery-result` artifact 上报。
5. `WorkbenchExtensionLifecycle` 先从本地订阅表移除并关闭句柄，再通知外部 observer；
   observer 异常不影响流关闭。
6. 本地 Order 模拟器识别完整 SSE frame 中的 `recovery-result` 后结束该转发，确保关流
   也传递到 OMC；没有抢通结果的订阅继续保持到显式取消或工作台关闭。

Authorization-T 模板支持新增、修改、删除、查询。示例 OMC 用一条内存白名单演示：
新增/修改替换当前策略，删除清空，查询不修改状态。生产集成方应在业务回调中持久化、
按策略标识精确修改/删除并返回查询 artifact。

## 8. 关闭顺序

1. Spring context 关闭时，`SpringWorkbenchExtensionLifecycle` 关闭剩余订阅和 Notification transport。
2. 单次 `WorkbenchOrchestrator` 在 `finally` 中关闭 Task-T transport。
3. Demo 关闭 Spring context、两个 OMC server，以及当前模式启用的本地 gateway/simulator。

正常工作流完成不能作为 Notification-T 订阅关闭条件。
