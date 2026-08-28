# 未发布 A2A-T SDK 依赖说明

执行引擎首发版本只支持下列最新 A2A-T SDK 源码基线，不提供旧 SDK 兼容层：

- 仓库：`project-openan/a2a-t-sdk-java`
- Git commit：`0de5a2751781419820436e5eb17cffc39b9db47d`
- 本地 Maven 版本：`1.0.0-0de5a27`（精确提交构建的不可变本地版本）
- 使用组件：`a2a-t-client`、`a2a-t-server` 及其传递依赖

## 已知上游生命周期缺口

该提交中的 `A2ATClient`、`A2ATServer` 和 `LLMClient` 尚未实现
`AutoCloseable`，因此调用过 OpenAI 兼容 LLM 后，底层 OpenAI Java 客户端的
`DefaultSleeper` 定时线程无法由工作流引擎通过公共 API 关闭。`SpringSpnDemo` 的
Task-T、Authorization-T、Notification-T、直连和指令平台业务链路均可正常完成，
但一次性关闭内嵌 Tomcat 时会提示这一条上游线程生命周期告警。

上游 SDK 应在新提交中补充幂等 `close()`，由 `A2ATClient/A2ATServer` 级联关闭其拥有的
LLM provider；执行引擎届时应升级到该新提交对应的新 Maven 版本。禁止在不改变版本号的
情况下覆盖 `1.0.0-0de5a27`，否则 IDEA 与 CI 将得到不可复现的同坐标异内容制品。

该 SDK 尚未发布到 Maven Central。引擎不探测、不加载也不降级到其他 A2A-T SDK 版本；
本机与 CI 都必须安装这里锁定的提交派生坐标。

## Windows / PowerShell 安装

```powershell
git clone https://github.com/project-openan/a2a-t-sdk-java.git
Set-Location a2a-t-sdk-java
git checkout 0de5a2751781419820436e5eb17cffc39b9db47d
mvn -B "-Drevision=1.0.0-0de5a27" -DskipTests `
  -pl a2a-t-client,a2a-t-server -am install
```

随后在执行引擎目录执行：

```powershell
mvn -B clean verify
```

`dev` 分支的 `samples` 还依赖东信 `com.eastcom.apollo:order-shaded-client:1.1.18`。
该包不在 Maven Central，运行完整 sample 测试前还必须按《指令平台适配指南》配置企业
Maven 仓库或安装 jar；`main` 分支的直连模式不需要该依赖。

## 升级 A2A-T SDK

升级时必须同步修改三处，且用全量测试证明一致性：

1. 根 `pom.xml` 的 `a2a.t.sdk.version` 和 `a2a.t.sdk.git.revision`；
2. `.github/workflows/ci.yml` 的 `A2AT_SDK_REF` 和 `A2AT_SDK_VERSION`；
3. 本文及 README 的安装命令。

禁止只覆盖本地同版本 jar。新版本至少要验证 Task-T 生成/校验、无状态 Negotiation-T
metadata、Authorization-T、Notification-T 长连接，以及两条现行传输路径：

1. `main` 验证直连 OMC 的 SpringSpnDemo 端到端流程；
2. `dev` 同时验证直连 OMC 和东信 Order 指令平台模拟器的 SpringSpnDemo 端到端流程。

“只支持最新 A2A-T SDK”仅表示不兼容旧 SDK 版本和旧协议格式，绝不表示取消直连或
Order 任一传输能力，也不允许省略双传输链路测试。

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

Authorization-T 只接受当前 SDK 定义的规范数据形态：单条策略字段使用全角逗号 `，`，
多条策略使用全角分号 `；`。旧 `/` 分隔格式不再接受。
