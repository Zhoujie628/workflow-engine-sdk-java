# A2A-T SDK 正式版本依赖说明

执行引擎首发版本只支持下列 A2A-T SDK 正式版本基线，不提供旧 SDK 兼容层：

- 仓库：`project-openan/a2a-t-sdk-java`
- 发布标签：`v1.1.0`，commit：`e42c83acce3e5ac2c245d36546ced0fa017b2b58`
- Maven Central 版本：`1.1.0`，groupId `net.openan.a2a-t.sdk`
- 引擎仅使用 `a2a-t-core`；samples／宿主显式引入 `a2a-t-client`，OMC 接收端使用 `a2a-t-server`。纯引擎消费者不依赖
  LLM/prompt/resources。

## 已知上游生命周期缺口

该正式版本中的 `A2ATClient`、`A2ATServer` 和 `LLMClient` 尚未实现
`AutoCloseable`，因此调用过 OpenAI 兼容 LLM 后，底层 OpenAI Java 客户端的
`DefaultSleeper` 定时线程无法由工作流引擎通过公共 API 关闭。`SpringSpnDemo` 的 离线协议与双路径模拟业务测试不受此限制；真实模型调用后的生命周期需要额外观察，
但一次性关闭内嵌 Tomcat 时会提示这一条上游线程生命周期告警。

上游 SDK 应在新提交中补充幂等 `close()`，由 `A2ATClient/A2ATServer` 级联关闭其拥有的 LLM provider；执行引擎届时应升级到该新提交对应的新
Maven 版本。禁止在不改变版本号的 情况下覆盖 `1.1.0`，否则 IDEA 与 CI 将得到不可复现的同坐标异内容制品。

该 SDK 已发布到 [Maven Central](https://repo.maven.apache.org/maven2/net/openan/a2a-t/sdk/a2a-t-client/1.1.0/)。 本机和
CI 直接解析正式制品，不再检出 SDK 源码或执行 SDK install，也不降级到其他版本。

## 当前模板发现的资源隔离

当前 SDK 的 PromptTemplateCatalog 会关闭 JarURLConnection 返回的 JarFile。 默认缓存可能让并行门面共用该句柄，导致 zip file
closed。 samples 宿主 A2ATInitialization 在初始化线程临时包装资源 URL，仅该连接 useCaches=false， 完成后恢复类加载器；不更改全局
URL 缓存或 SDK jar。 每次 SDK 升级需重新验证该隔离是否仍必要。

SDK 内容接口由宿主通过 StandardTemplates.*.uri () 选择模板；业务自行生成最终内容，再用 A2atMessages.from 包装。
引擎只检查协商上下文关联，不解释业务内容。 完整契约见 [业务回调集成契约](BUSINESS_CALLBACKS.md)。

## Maven / IDEA 接入

根 POM 的 `a2a.t.sdk.version` 是唯一依赖版本来源，当前为 `1.1.0`。 在执行引擎目录直接执行：

```powershell
mvn -B -U clean verify
```

IDEA 使用该工程的 Maven 配置并执行 Reload All Maven Projects 即可；无需手工安装 A2A-T jar。
如果企业或公共镜像尚未同步正式版本，应等待同步或按组织要求调整 Maven 镜像， 不要同坐标 install 本地构建物替代正式发布制品。本次通过本机镜像下载并核对
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

东信 `com.eastcom.apollo:order-shaded-client:1.1.18` 仍是独立的非 Central 依赖， 运行 dev 完整 samples
前仍需按《指令平台适配指南》配置企业制品库或安装供应商 jar。 main 直连不需要该依赖。

## 升级 A2A-T SDK

升级时更新根 `pom.xml` 的 `a2a.t.sdk.version`、本文发布标签和相关接入文档， 核对正式制品与源码／资源差异，并运行完整回归。CI
直接使用根 POM，不单独维护版本或编译 SDK。

禁止只覆盖本地同版本 jar。新版本至少要验证 Task-T 生成/校验、无状态 Negotiation-T metadata、Authorization-T、Notification-T
长连接，以及两条现行传输路径：

1. `main` 验证直连 OMC 的 SpringSpnDemo 端到端流程；
2. `dev` 同时验证直连 OMC 和东信 Order 指令平台模拟器的 SpringSpnDemo 端到端流程。

“只支持最新 A2A-T SDK”仅表示不兼容旧 SDK 版本和旧协议格式，绝不表示取消直连或 Order 任一传输能力，也不允许省略双传输链路测试。

## A2A Java SDK 基线

执行引擎使用 Maven Central 已正式发布的 `org.a2aproject.sdk` `1.2.0.Final`，不采用尚未 发布制品的源码标签或 SNAPSHOT。该版本将
`DefaultRequestHandler` 构造迁移到 Builder、 将任务校验收进请求处理器，并把 `Utils` 移到 `org.a2aproject.sdk.spec.util`
。同时必须让
`protobuf-java-util` 与其传递引入的 `protobuf-java:4.35.0` 对齐，避免 REST JSON 转换时 出现 `NoSuchMethodError`。Spring
starter 通过仅暴露 `RequestHandler` 接口的委托隔离
`DefaultRequestHandler` 中 CDI 专用注入点；SSE 端点使用 `SseEmitter` 输出规范事件，不能把 手工拼接的 `id:/data:`
文本再次作为普通响应体发送。升级 A2A Java SDK 后同样要跑直连和 Order 两条 E2E。

## 单一资源基线

模板、slot schema、slot extraction 和 content validation 提示词全部来自锁定的 SDK jar。 samples 不再用同名 classpath 资源覆盖
SDK 内容，也不保留旧 renderer/validator 的兼容提示词。 测试会核对实际加载资源来自 `a2a-t-resources`，避免升级后仍静默运行旧规则。

Authorization-T 只接受当前 SDK 定义的规范数据形态：每条策略以从 1 连续递增的编号 `1.` 开头， 字段为“字段名是值”，字段间使用全角逗号
`，`，多条策略用换行分隔。 例如：
`1. 业务场景是业务投诉诊断，处置类型是业务抢通，操作名称是隧道调优，有效期是2026-06-01~2030-06-18`。
日期范围与“永久生效”均支持；裸值列表、分号分隔和旧 `/` 格式不接受。 此前本节关于全角分号的描述不正确，已按 1.1.0 正式 jar 的
slot.json 修正。

## 示例业务接口调用复核（2026-08-31）

此前发现的正文来源、填充结果丢弃、City2 缺参路径、Reject／Abort 分发及离线宽松校验问题， 现已在 samples
业务层整改；通用引擎未重新引入内容生成或语义判断职责。

| 路径                       | 发送端生成                                                                           | 接收端校验／实际使用                                                                             |
|----------------------------|--------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| WAIMO → 集成方             | generateTaskPromptFromDataWithSchema                                                 | WorkbenchTaskInputParser 使用 validateTaskPromptAndDataFilling 的 filled.data                    |
| 集成方 → 两地市任务        | WorkbenchControlPoint.onTask：generateTaskPromptFromDataWithSchema                   | 两 OMC 校验 Task-T，使用 filled.data 构造 SpnTaskInput                                           |
| 两地市 OMC → 集成方协商    | generateNegotiationProposePromptFromData，按实际缺失字段生成                         | validateProposePromptAndDataFilling，按 items 从当前城市输入提取答案                             |
| 集成方 → OMC Accept        | generateNegotiationAcceptPromptFromData                                              | 从正式 metadata 取正文，validateAcceptPromptAndDataFilling 后合并请求字段并复核业务必填信息      |
| 集成方 → OMC Reject／Abort | 对应 generateNegotiationRejectPromptFromData／generateNegotiationAbortPromptFromData | 对应 validateRejectPromptAndDataFilling／validateAbortPromptAndDataFilling，结束任务且不执行诊断 |
| 独立授权                   | generateAuthPromptFromDataWithSchema                                                 | validateAuthPromptAndDataFilling 后构造并应用 AuthorizationPolicy                                |
| 独立订阅                   | generateNotificationPromptFromDataWithSchema                                         | validateNotificationPromptAndDataFilling 后构造 NotificationPolicy                               |

自然语言 FromText 和结构化 FromDataWithSchema 是替代入口，不是同一消息必须串行全部执行的步骤。 本 Demo 主路径选择结构化数据；Task-T
自然语言另有测试，不宣称覆盖 SDK 全部公开接口。 端口资源归属、投诉分类和 OSS 流水号是 SPN 业务层规则；正式集成应替换为宿主／OMC
的实际业务判断。

协商校验不再读取 parts 摘要；会核对 A2A taskId/contextId 和协商 id/round/maxRounds。 SDK 拒绝原始任务时，不使用未经验证的局部数据，重新协商完整必要字段。
接收 Accept 后只合并本轮请求字段；错误正文、缺参和跨协商回复均不能进入诊断。

1.1.0 的授权／通知生成入口面向授权操作及订阅请求，并不是任意业务结果生成接口。 诊断结果、授权回执和抢通事件由 OMC
业务产生。RecoveryNotification 从正式 Notification-T artifact metadata 检查 SPN 最终抢通结果，避免仅凭 artifact
名称／摘要就关闭订阅。 这不是对任意外部通知的通用语义验证，也不在工作流引擎内实施。

完整角色映射、字段规则、FromText／FromData 选择、宿主拒绝策略和可重复验证范围见
[业务回调指南第 9 节](BUSINESS_CALLBACKS.md#9-示例业务侧的-a2a-t-110-调用参考)。 严格离线测试只处理明确样例输入，不从模板示例臆造参数；不能代替真实
LLM 和现网 OMC 联调。

## 发布验证入口

执行 `mvn -B clean verify`，必须包含 core、starter 与 samples；测试统计以当前提交的 Surefire XML 为准。
main 必须通过直连 SpringSpnDemo；dev 还必须通过具备合法供应商 jar 的 Order 模拟平台路线。
默认本地演示为 City1 缺参协商、City2 完整输入；外部 OMC 不注入演示缺参。

SSE pretty 展示保留事件控制字段，JSON 数据区明确标注为展示格式，不逐行重复 data:。
设置 `WORKFLOW_ENGINE_PROTOCOL_PRETTY=false` 可查看脱敏后的原文，容量限制仍生效。
Header 凭据始终脱敏，编排中心 query token 仅显示匿名标记，不存在敏感头放开开关。

离线 LLM provider、本地 OMC 和 Order 模拟器结果不能代替真实模型／平台／OMC 验收。
真实环境验收须单独记录配置来源、版本、操作授权和脱敏报文，私有联调记录不得提交到公共仓库。
