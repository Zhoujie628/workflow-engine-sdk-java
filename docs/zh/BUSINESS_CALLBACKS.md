# 业务回调集成契约

首发 API，A2A-T SDK `1.1.0`，A2A Java `1.2.0.Final`。 引擎负责 DAG、标准 A2A 信封、认证、传输和任务等待；宿主负责消息内容、schema、模板、语义校验和
LLM。业务契约独立于具体传输实现。

## 1. 回调接口

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

示例显式发送普通文本，不宣称为 Task-T；本地透传、路由和停止策略需替换为真实业务逻辑。 onTask 只返回内容，不自行再次发起任务网络请求。
导入 `dev.openan.workflow.engine.control.ControlPoint`、`model.*`， 以及 Java 的 List、Map、CompletableFuture。

## 2. TaskRequest 与上游窗口

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

| contextFrom   | 上游选择                         |
|---------------|----------------------------------|
| 未指定 / null | 已产生结果的直接前驱             |
| []            | 不聚合上游，runtimeIntent 仍保留 |
| ["*"]         | 已产生结果的全部祖先             |
| 显式祖先名称  | 按声明顺序选取对应结果           |

contextFrom 只选择证据，不建立执行依赖；依赖由 next 定义。 未知或非祖先名称、通配符与名称混用均非法；未激活分支不虚构结果。
引擎不把窗口附加到 instruction/parts，也不调用 LLM 映射上游；由宿主决定怎么消费或映射下游输入。

窗口结构：stepName → taskResults[] → outputs[] / receivedMessages[]。 TaskExecutionResult 还保留 agentName、skill、逻辑
taskId、taskDescription、status、error、errorCode、errorDetails。多子任务不混合，嵌套数组仍作为一个输出项。输出不要求来自
LLM，也不要求符合特定领域模板。

## 3. 最终内容与完整响应

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

## 4. 宿主调用 A2A-T

纯引擎仅依赖 a2a-t-core；生成内容的宿主显式依赖 a2a-t-client。 宿主创建、配置自己的 A2ATClient，不通过
WorkflowEngineClientConfig 或 ExecutePsop 承载 LLM 配置。

```java
// sdk、data、schema、模板选择均属于宿主智能体。
MetadataContent generated = sdk.generateTaskPromptFromDataWithSchema(
    data, schema, StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
MessageContent outgoing = A2atMessages.from(generated, List.of(new TextPart("处理当前任务")));
```

自然语言入口为 `sdk.generateTaskPromptFromText(text, StandardTemplates.PRIVATE_LINE_COMPLAINT.uri())`，随后同样调用 A2atMessages.from。

自然语言、结构化生成和校验等能力直接调用宿主 SDK。A2atMessages.from 只复制生成的 metadata 并激活对应扩展，parts 保持业务提供的内容；不生成、不校验业务语义。应选择当前 A2A-T SDK 发布的模板 URI，不自行构造。样例初始化工具属于宿主示例，不是引擎依赖。

## 5. 协商

NegotiationRequest(task, originalSubmission, received, previousExchanges, remainingWait)： task 是原始
TaskRequest，originalSubmission 是首次最终提交， received 是当前完整响应，previousExchanges 只含该会话的 Exchange
(received, reply)， remainingWait 是本次交互剩余时间。引擎不规定业务 proposal 分类或 schema。

只有远端 `INPUT_REQUIRED` 携带有效 Negotiation-T Propose 才进入 `onNegotiation`。 终态不会重启协商，普通 INPUT_REQUIRED
明确报告不支持的交互。 宿主自行校验、理解 Propose，并用自己的 A2A-T client 生成最终 Accept/Reject/Abort。 通过
`A2atMessages.contextOf(request.received())` 取得收到的上下文； 结束回复保持相同 id、round、maxRounds，最后允许的一轮仍可回答，不自行
nextRound 或返回新 Propose。

返回 `new NegotiationReply.Send(content)` 发送最终内容； 返回 `new NegotiationReply.Stop(code, reason)` 只在本地停止，不生成
Abort。 同一任务／会话／轮次的重复等待事件不会重复回调、重复提交；未变化状态通过 getTask 观察。
`maxNegotiationExchanges` 默认 3，是独立于 SDK context.maxRounds 的本地交互资源预算。 超时、预算耗尽、回调缺失均明确失败，不默认
Accept，也不自动生成 Abort。 Accept/Reject 的 SUBMITTED/WORKING ACK 仍需等待任务结果，不重发原命令。 业务发送 Abort 后，即使远端用
COMPLETED 确认，也不能判为任务成功。

业务可调用 validateProposePromptAndDataFilling，再选择 SDK 强类型 fromData 或自然语言生成接口。
不得虚构缺失业务事实；只从当前任务的权威输入中提取实际请求的字段。SDK 内容异常由宿主映射成 BusinessFailure(code,
safeMessage, safeDetails)； 引擎不识别 SDK 专属内容异常类，也不自动转存可能含敏感数据的原异常。

## 6. 路由、并发与失败

RouteRequest(executionId, stepName, workflowInput, currentResults, candidates)。 候选为 RouteOption(nextStep,
condition)，返回 RouteDecision.builder().nextStep(允许目标).build()。 无条件边自动并行推进，条件分支只接受候选中的目标。

回调可能并发，不要共享可变的“当前任务”状态。每个工作流任务从内容准备到传输完成受客户端 timeout 总体限制，默认
sendTimeoutSeconds=600； 路由单独限制回调等待时间，dispatch／协商另有总等待截止时间。取消／超时后晚到结果不发送，
但不等于自动取消宿主正在运行的 LLM 或业务操作，宿主负责清理其资源。 同步回调入口应迅速返回，阻塞任务应交给异步执行器。
回调缺失、返回 null 或异常均明确失败；不确定发送失败不自动重发。

## 7. 独立授权与订阅

ExtensionSender.sendAuthorization(agentName, finalContent) 返回 CompletableFuture<SendMessageResult>。
ExtensionSender.openNotification(agentName, finalContent, (handle, received) -> ...) 立即返回 handle。
传入宿主生成的最终授权／订阅内容并激活对应扩展。 I/O 前注册 handle，早到事件可直接 handle.close()，不用捕获尚未赋值的外部变量。
acknowledgement() 代表真实 ACK 或失败；超时不伪造成功。 close() 请求关闭，completion() 在流实际退出后完成。
订阅保持到宿主定义的终态事件、显式取消或宿主退出，不随单次工作流结束自动关闭。

任务、授权、通知使用独立 transport/runtime/context。授权和订阅成功与否均不作为工作流执行前提；授权策略只控制与该策略关联的宿主自定义业务操作。

## 8. 验证和日志

执行 `mvn -B clean verify`。Reactor 覆盖回调契约、完整响应组装、协商关联、独立扩展生命周期、失败传播、取消和协议日志脱敏。测试使用受控测试数据，不覆盖 SDK schema 或模板；这些是发布回归证据，不等于生产端点或真实模型语义认证。日志与认证配置见 [集成指南](INTEGRATION_GUIDE.md)。

## 9. 业务侧 A2A-T 1.1.0 调用参考

引擎只调度最终 A2A 消息；生成、校验、填充和业务判断属于宿主智能体与被调度智能体的业务代码。每个角色只调用自己所有消息需要的 API。

| 业务位置                          | 生成／接收校验入口                                                                                      | 对结果的业务使用 |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------|----------------------|
| 宿主智能体入站任务            | validateTaskPromptAndDataFilling                                                                            | 使用已校验 `filled.data` 选择工作流并构建业务输入 |
| onTask → 被调度智能体         | generateTaskPromptFromDataWithSchema 或 generateTaskPromptFromText → validateTaskPromptAndDataFilling | 从已校验数据构建输入，不直接使用扩展原文 |
| 被调度智能体 → 宿主协商     | generateNegotiationProposePromptFromData 或 FromText → validateProposePromptAndDataFilling            | 仅从当前任务权威数据回答实际请求项 |
| 宿主 Accept → 被调度智能体  | generateNegotiationAcceptPromptFromData 或 FromText → validateAcceptPromptAndDataFilling              | 合并已校验的请求字段，再执行领域校验 |
| 宿主 Reject / Abort → 被调度智能体 | 阶段对应生成入口 → 阶段对应校验入口                                                            | 校验原因并终止当前任务路径 |
| 独立授权                          | generateAuthPromptFromDataWithSchema → validateAuthPromptAndDataFilling                                    | 从 `filled.data` 构建并应用 AuthorizationPolicy |
| 独立订阅                          | generateNotificationPromptFromDataWithSchema → validateNotificationPromptAndDataFilling                    | 构建 NotificationPolicy 并保持独立连接 |

有结构化业务参数时使用上述 FromDataWithSchema；只有自然语言时，业务可改用 generateTaskPromptFromText(text, templateUri)
、generateAuthPromptFromText(text, templateUri)、 generateNotificationPromptFromText(text, templateUri)。协商同时提供各阶段
FromText 和强类型 FromData。 这些是输入方式的选择，不是每条消息必须全部执行的流水线。FromDataWithSchema 也可能调用 SDK 配置的
LLM， 不能等同于完全不调用模型。

无论选择哪一种生成入口，都保留返回 MetadataContent 的完整 metadata，再通过 A2atMessages.from(generated, List.of (new
TextPart("业务摘要"))) 转成引擎 MessageContent。 parts 必须有合法内容；摘要不是正式扩展正文，不能拿摘要替代校验输入。不要只复制
promptText 而丢失 templateUri、negotiationContext 或扩展 URI。
回复必须匹配 A2A taskId/contextId 及协商 id/round/maxRounds，正式正文从 Negotiation-T metadata 读取。
一次有效回复只消费一次。正文错误、字段不完整、任务不匹配或跨协商的回复不得执行业务。无法核实权威信息时生成 SDK Abort，不编造数据；宿主披露策略拒绝时生成 SDK Reject。授权策略是独立关注点。

Authorization-T 和 Notification-T 的生成接口用于生成请求，不用于包装任意业务结果。授权回执与通知事件是被调度智能体的业务输出，必须遵循选定模板和应用契约。订阅只在宿主定义的终态事件、显式取消或关闭时结束；ACK 本身不是终态业务事件。SDK 拒绝应保留安全的错误码、slot 错误与已提取参数键。授权或订阅失败仍不阻断工作流执行。
