# A2A-T SDK 正式版本依赖说明

执行引擎首发版本只支持下列 A2A-T SDK 正式版本基线，不提供旧 SDK 兼容层：

- 仓库：`project-openan/a2a-t-sdk-java`
- 发布标签：`v1.1.0`，commit：`e42c83acce3e5ac2c245d36546ced0fa017b2b58`
- Maven Central 版本：`1.1.0`，groupId `net.openan.a2a-t.sdk`
- 引擎使用 `a2a-t-core`；samples／宿主显式使用 `a2a-t-client`，OMC 接收端使用 `a2a-t-server`。引擎不传递引入内容生成、LLM、prompt/resources。

## 已知上游生命周期缺口

该正式版本中的 `A2ATClient`、`A2ATServer` 和 `LLMClient` 尚未实现
`AutoCloseable`，因此调用过 OpenAI 兼容 LLM 后，底层 OpenAI Java 客户端的
`DefaultSleeper` 定时线程无法由工作流引擎通过公共 API 关闭。`SpringSpnDemo` 的
离线协议测试不受此限制；真实模型调用后的生命周期仍需额外观察，
一次性关闭内嵌 Tomcat 时可能提示上游线程生命周期告警。

上游 SDK 应在新提交中补充幂等 `close()`，由 `A2ATClient/A2ATServer` 级联关闭其拥有的
LLM provider；执行引擎届时应升级到该新提交对应的新 Maven 版本。禁止在不改变版本号的
情况下覆盖 `1.1.0`，否则 IDEA 与 CI 将得到不可复现的同坐标异内容制品。

该 SDK 已发布到 [Maven Central](https://repo.maven.apache.org/maven2/net/openan/a2a-t/sdk/a2a-t-client/1.1.0/)。
本机和 CI 直接解析正式制品，不再检出 SDK 源码或执行 SDK install，也不降级到其他版本。

## 当前模板发现的资源隔离

当前 SDK 的 PromptTemplateCatalog 会关闭 JarURLConnection 返回的 JarFile。
默认缓存可能让并行门面共用该句柄，导致 zip file closed。
samples 宿主 A2ATInitialization 在初始化线程临时包装资源 URL，仅该连接 useCaches=false，
完成后恢复类加载器；不更改全局 URL 缓存或 SDK jar。
每次 SDK 升级需重新验证该隔离是否仍必要。

SDK 当前内容接口接受字符串模板 URI，宿主通过 StandardTemplates.*.uri() 指定。
业务调用生成／校验 API，再经 A2atMessages.from 转为最终 MessageContent。
引擎只检查协商上下文关联，不解释业务内容。
完整契约见 [业务回调集成契约](BUSINESS_CALLBACKS.md)。

## Maven / IDEA 接入

根 POM 的 `a2a.t.sdk.version` 是唯一依赖版本来源，当前为 `1.1.0`。
在执行引擎目录直接执行：

```powershell
mvn -B -U clean verify
```

IDEA 使用该工程的 Maven 配置并执行 Reload All Maven Projects 即可；无需手工安装 A2A-T jar。
如果企业或公共镜像尚未同步正式版本，应等待同步或按组织要求调整 Maven 镜像，
不要同坐标 install 本地构建物替代正式发布制品。本次通过本机镜像下载并核对
SDK jar 的 SHA-1 与 Central 发布校验值一致。

宿主若调用 A2A-T 内容生成，需显式声明：

```xml
<dependency>
    <groupId>net.openan.a2a-t.sdk</groupId>
    <artifactId>a2a-t-client</artifactId>
    <version>1.1.0</version>
</dependency>
```

需要接收端校验时同样显式添加 `a2a-t-server:1.1.0`；samples 已声明二者。

## 升级 A2A-T SDK

升级时更新根 `pom.xml` 的 `a2a.t.sdk.version`、本文发布标签和相关接入文档，
核对正式制品与源码／资源差异，并运行完整回归。CI 直接使用根 POM，不单独维护版本或编译 SDK。

禁止只覆盖本地同版本 jar。新版本至少要验证 Task-T 生成/校验、无状态 Negotiation-T
metadata、Authorization-T、Notification-T 长连接，以及现行传输路径：
验证直连 OMC 的 SpringSpnDemo 端到端流程。

“只支持最新 A2A-T SDK”仅表示不兼容旧 SDK 版本和旧协议格式。

## A2A Java SDK 基线

执行引擎使用 Maven Central 已正式发布的 `org.a2aproject.sdk` `1.2.0.Final`，不采用尚未
发布制品的源码标签或 SNAPSHOT。该版本将 `DefaultRequestHandler` 构造迁移到 Builder、
将任务校验收进请求处理器，并把 `Utils` 移到 `org.a2aproject.sdk.spec.util`。同时必须让
`protobuf-java-util` 与其传递引入的 `protobuf-java:4.35.0` 对齐，避免 REST JSON 转换时
出现 `NoSuchMethodError`。Spring starter 通过仅暴露 `RequestHandler` 接口的委托隔离
`DefaultRequestHandler` 中 CDI 专用注入点；SSE 端点使用 `SseEmitter` 输出规范事件，不能把
手工拼接的 `id:/data:` 文本再次作为普通响应体发送。升级 A2A Java SDK 后同样要跑直连和
Order 两条 E2E。

## 单一资源基线

模板、slot schema、slot extraction 和 content validation 提示词全部来自锁定的 SDK jar。
samples 不再用同名 classpath 资源覆盖 SDK 内容，也不保留旧 renderer/validator 的兼容提示词。
测试会核对实际加载资源来自 `a2a-t-resources`，避免升级后仍静默运行旧规则。

Authorization-T 只接受当前 SDK 定义的规范数据形态：每条策略以从 1 连续递增的编号 `1.` 开头，
字段为“字段名是值”，字段间使用全角逗号 `，`，多条策略用换行分隔。
例如：`1. 业务场景是业务投诉诊断，处置类型是业务抢通，操作名称是隧道调优，有效期是2026-06-01~2030-06-18`。
日期范围与“永久生效”均支持；裸值列表、分号分隔和旧 `/` 格式不接受。
此前本节关于全角分号的描述不正确，已按 1.1.0 正式 jar 的 slot.json 修正。

## 1.1.0 升级核对

相对此前 `ec14175` 源码基线，`v1.1.0` 的 core/client/server/llm/prompt/resources/negotiation
模块源码与资源没有差异；无需修改业务回调或增加版本兼容层。上述 JarFile 初始化隔离仍有必要，
LLM close 公共接口缺口也未随发布解决。发布之后当前 upstream/main 的新增差异仅为开发指南版本说明。

2026-08-31 本地发布制品回归：main 188 项、dev 239 项测试通过，均无失败、错误或跳过。
覆盖正常输入与缺参协商的直连 SpringSpnDemo，dev 另覆盖 Order SDK 平台模拟路线。
SDK 模板和校验使用正式 jar，LLM 使用离线 provider，OMC／平台使用本地模拟器；未验证真实模型或现网。
依赖树确认纯引擎只有 a2a-t-core，samples 的全部 A2A-T 组件均为 1.1.0。
构建日志位于各工作树 `logs/a2at-1.1.0-*-verify.log`，逐项报告在各模块 `target/surefire-reports/`。

## 示例业务接口调用复核（2026-08-31）

此前发现的正文来源、填充结果丢弃、City2 缺参路径、Reject／Abort 分发及离线宽松校验问题，
现已在 samples 业务层整改；通用引擎未重新引入内容生成或语义判断职责。

| 路径 | 发送端生成 | 接收端校验／实际使用 |
|---|---|---|
| WAIMO → 工作台 | generateTaskPromptFromDataWithSchema | WorkbenchTaskInputParser 使用 validateTaskPromptAndDataFilling 的 filled.data |
| 工作台 → 两地市任务 | WorkbenchControlPoint.onTask：generateTaskPromptFromDataWithSchema | 两 OMC 校验 Task-T，使用 filled.data 构造 SpnTaskInput |
| 两地市 OMC → 工作台协商 | generateNegotiationProposePromptFromData，按实际缺失字段生成 | validateProposePromptAndDataFilling，按 items 从当前城市输入提取答案 |
| 工作台 → OMC Accept | generateNegotiationAcceptPromptFromData | 从正式 metadata 取正文，validateAcceptPromptAndDataFilling 后合并请求字段并复核业务必填信息 |
| 工作台 → OMC Reject／Abort | 对应 generateNegotiationRejectPromptFromData／generateNegotiationAbortPromptFromData | 对应 validateRejectPromptAndDataFilling／validateAbortPromptAndDataFilling，结束任务且不执行诊断 |
| 独立授权 | generateAuthPromptFromDataWithSchema | validateAuthPromptAndDataFilling 后构造并应用 AuthorizationPolicy |
| 独立订阅 | generateNotificationPromptFromDataWithSchema | validateNotificationPromptAndDataFilling 后构造 NotificationPolicy |

自然语言 FromText 和结构化 FromDataWithSchema 是替代入口，不是同一消息必须串行全部执行的步骤。
本 Demo 主路径选择结构化数据；Task-T 自然语言另有测试，不宣称覆盖 SDK 全部公开接口。
端口资源归属、投诉分类和 OSS 流水号是 SPN 业务层规则；正式集成应替换为宿主／OMC 的实际业务判断。

协商校验不再读取 parts 摘要；会核对 A2A taskId/contextId 和协商 id/round/maxRounds。
SDK 拒绝原始任务时，不使用未经验证的局部数据，重新协商完整必要字段。
接收 Accept 后只合并本轮请求字段；错误正文、缺参和跨协商回复均不能进入诊断。

1.1.0 的授权／通知生成入口面向授权操作及订阅请求，并不是任意业务结果生成接口。
诊断结果、授权回执和抢通事件由 OMC 业务产生。RecoveryNotification 从正式 Notification-T artifact
metadata 检查 SPN 最终抢通结果，避免仅凭 artifact 名称／摘要就关闭订阅。
这不是对任意外部通知的通用语义验证，也不在工作流引擎内实施。

完整角色映射、字段规则、FromText／FromData 选择、宿主拒绝策略和可重复验证范围见
[业务回调指南第 9 节](BUSINESS_CALLBACKS.md#9-示例业务侧的-a2a-t-110-调用参考)。
严格离线测试只处理明确样例输入，不从模板示例臆造参数；不能代替真实 LLM 和现网 OMC 联调。

业务调用整改阶段的验证记录（后续默认演示及日志展示变更见下节）：两分支均执行 `mvn -q test`；
补充端口修正／原上下文保留用例后，又分别执行
`mvn -q -pl samples -am "-Dtest=EmbeddedA2AServerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`。
最终 Surefire 汇总为 main 202、dev 254 项，失败／错误／跳过均为 0。
dev 的直连及 Order Spring E2E 各 3 项，接收端真实 SDK 往返用例共 13 项。
主测试日志位于各运行目录 `logs/business-sdk-*-verified.log`；最后补充用例日志为
`logs/business-sdk-*-input-preservation.log`，详细报告位于各模块 `target/surefire-reports/`。

## 默认演示与 SSE 展示更新（2026-08-31）

本地 SpringSpnDemo 无需 VM 参数即演示 City1 缺参协商、City2 完整输入直接诊断；
`-Da2at.samples.negotiation=false` 关闭演示。设置通过当前 Spring 应用实例传递，
不修改 JVM 全局开关，普通宿主默认关闭；外部 OMC 模式默认关闭并拒绝显式注入。

SSE pretty 日志保留事件控制字段，将 JSON 单独放在标明非原始报文的数据区，不再逐行重复 `data:`。
原始观测正文保持不变，`WORKFLOW_ENGINE_PROTOCOL_PRETTY=false` 可查看脱敏后的原始正文；
非 JSON／不完整正文保持原样，脱敏和容量限制仍生效。

两分支分别执行 `mvn -q test`：main 208 项、dev 261 项，失败／错误／跳过均为 0。
直连及 Order Spring E2E 各 4 项，包括默认仅 City1 协商、显式开关及双城市额外回归；
另有外部 OMC 禁止注入、无全局设置污染、SSE 原文不变及事件边界测试。
日志为各分支 `logs/demo-display-main-verified.log` 或 `logs/demo-display-dev-verified.log`。
仍为真实 SDK 加离线 LLM／本地 OMC 和平台模拟器验证，不代表现网模型或设备联调。

## 授权格式与协商字段回归（2026-08-31 09:28）

按 1.1.0 正式资源修正授权编号、字段标签、换行和删除选择器，加入直接读取 jar 内多条策略示例的测试。
真实运行出现的“本地市实际接入端口名称”提取结果已纳入离线 fixture；协商回复保留经 SDK
语义校验的原始编号信息项名称。详情见 [业务回调指南](BUSINESS_CALLBACKS.md)。

最终版本全量验证：main 221 项、dev 274 项，失败／错误／跳过均为 0（统计包含 Spring starter 模块）。
main 直连与 dev 直连、Order 平台模拟路线均通过；默认场景确认 City1 Propose/Accept、
City2 无协商，两个 OMC 授权成功、订阅成功，工作流完成并释放连接。
日志为 main 工作树 `logs/auth-canonical-main-verify.log` 和 dev 工作树
`logs/auth-canonical-dev-verify.log`，JUnit 明细位于各模块 `target/surefire-reports/`。

这些结果基于正式 SDK jar、离线 LLM provider 和本地 OMC／Order 模拟器，不是现网或真实模型验收。
Notification-T 同步补齐失败原因字段名和拒绝详情日志，但历史日志没有 slot 错误详情，
不能断言此前真实模型拒绝订阅的唯一原因已经确认；需真实模型重跑核验。
