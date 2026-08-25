# 未发布 A2A-T SDK 依赖说明

当前执行引擎不是按“文档里写的旧接口”兼容，而是严格按下列 A2A-T SDK 源码版本编译和测试：

- 仓库：`Zhoujie628/a2a-t-sdk-java`
- Git commit：`0ef79d37f49a9b7a2dbe16b6d9fd1ccdb6d9538d`
- 本地 Maven 版本：`1.0.0-0ef79d3`（精确提交构建的不可变本地版本）
- 使用组件：`a2a-t-client`、`a2a-t-server` 及其传递依赖

## 已知上游生命周期缺口

该固定提交中的 `A2ATClient`、`A2ATServer` 和 `LLMClient` 尚未实现
`AutoCloseable`，因此调用过 OpenAI 兼容 LLM 后，底层 OpenAI Java 客户端的
`DefaultSleeper` 定时线程无法由工作流引擎通过公共 API 关闭。`SpringSpnDemo` 的
Task-T、Authorization-T、Notification-T、直连和指令平台业务链路均可正常完成，
但一次性关闭内嵌 Tomcat 时会提示这一条上游线程生命周期告警。

上游 SDK 应在新提交中补充幂等 `close()`，由 `A2ATClient/A2ATServer` 级联关闭其拥有的
LLM provider；执行引擎届时应升级到该新提交对应的新 Maven 版本。禁止在不改变版本号的
情况下覆盖 `1.0.0-0ef79d3`，否则 IDEA 与 CI 将得到不可复现的同坐标异内容制品。

该 SDK 尚未发布到 Maven Central。引擎刻意不再复用含义不明确的本地 `1.0.0`，以免 Maven
静默选中带有 `startNegotiation` 等废弃接口的旧 jar。

## Windows / PowerShell 安装

```powershell
git clone https://github.com/Zhoujie628/a2a-t-sdk-java.git
Set-Location a2a-t-sdk-java
git checkout 0ef79d37f49a9b7a2dbe16b6d9fd1ccdb6d9538d
mvn -B "-Drevision=1.0.0-0ef79d3" -DskipTests `
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
metadata、Authorization-T、Notification-T 长连接，以及直连和东信转发两条端到端路径。

## 当前内容校验兼容层

锁定版本的 `TaskPromptRenderer` 会把 slot 模板折叠为简洁自然语言，但 SDK 自带的
`content_validation` 提示词仍要求输出复现完整模板结构；在当前示例模型上，这会使 SDK
官方 Authorization-T 成功样例也被误判为缺字段。samples 模块通过同名 classpath 资源
覆盖 `prompt_resources/prompts/content_validation/{zh-CN,en-US}`，仍调用 SDK 的
`validate*PromptAndDataFilling`，只把语义判定标准校准为 renderer 的真实输出契约。

升级 SDK 时必须先运行其官方 Authorization-T/Notification-T 样例；若上游已统一 renderer
与 validator，应删除该覆盖并执行本仓库全量测试及两种传输模式的端到端验证。
