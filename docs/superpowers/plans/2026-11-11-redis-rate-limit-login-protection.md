# Redis 限流与登录防刷 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在不改原秒杀库存和异步下单逻辑的前提下，完成可验证的多维限流、验证码防刷和当前 Token 退出。

**Architecture:** Controller 注解/AOP 负责接口准入；一次复合 Lua 完成秒杀两个固定窗口与一个严格冷却的全有或全无检查。Service 调用验证码专用组件完成签发、短信结果处理、原子校验和消费；Token 组件服务于现有拦截器。所有原子性以当前单实例 Redis 为前提。

**Tech Stack:** Java 8、Spring Boot 2.3.12.RELEASE、MyBatis-Plus 3.4.3、spring-data-redis 2.6.2、Lettuce 6.1.6.RELEASE、Redisson 3.13.6、Lua、JUnit 5、Mockito、Spring Test/MockMvc。

**Spec:** [2026-11-11-redis-rate-limit-login-protection-design.md](../specs/2026-11-11-redis-rate-limit-login-protection-design.md)。执行者必须先读设计和本计划，不能只看单个 Task。

## 本轮实施记录（2026-11-11）

用户最新授权已覆盖旧文档中的“逐 Task 暂停”和“本轮仅写文档”：本轮按依赖顺序连续完成 Task 1～7。用户要求不修改 application.yaml，因此使用 ProtectionProperties 默认值和启动时敏感日志保护；未覆盖原配置。

用户明确同意复用现有 YAML 中 Redis 连接，在 **DB15** 运行隔离测试。测试新增显式的临时 Maven 参数 `-Dhmdp.test.redis.config=src/main/resources/application.yaml` 与 `-Dhmdp.test.redis.database=15`，不需要任何 Windows 系统环境变量。测试夹具只读连接值，拒绝测试库与业务逻辑库相同，只清理已登记的唯一测试前缀 Key。

下面步骤的完成标记指实现、测试与检查已完成；旧暂停步骤按新授权连续执行。Git 暂存/提交属于本轮未执行的集成动作，原提交建议仅保留为建议；工作区中的实现、文档和执行证据均保留，未将用户改动暂存、提交、合并或推送。

| Task | 当前状态 | 实际分阶段测试（含必要依赖回归） |
| --- | --- | --- |
| 1 | 已完成 | 17 项通过，失败/错误/跳过均 0，退出码 0 |
| 2 | 已完成 | 11 项通过，失败/错误/跳过均 0，退出码 0 |
| 3 | 已完成 | 34 项通过，失败/错误/跳过均 0，退出码 0 |
| 4 | 已完成 | 16 项通过，失败/错误/跳过均 0，退出码 0 |
| 5 | 已完成 | 13 项通过，失败/错误/跳过均 0，退出码 0 |
| 6 | 已完成 | 11 项通过，失败/错误/跳过均 0，退出码 0 |
| 7 | 已完成 | 9 项通过；最终整体 84 项通过，失败/错误/跳过均 0，退出码 0 |

独立审阅发现的重复代理头、短信字符串损坏状态及 Token ID 上界问题均已修正。Lua 回归经受控回退验证（三项预期失败）后恢复；最终真实 DB15 测试全绿。未重审已由回归证明的修正。

最终命令、分阶段日志和技术解释见 [中文开发总结](../../2026-11-11-redis-rate-limit-login-protection-summary.md)。最终原始日志在 `target/protection-execution/final-verified.log`，JUnit XML 位于 `target/surefire-reports/`。

## Global Constraints

- 保持现有 Controller → Service → Mapper 分层；限流在 Controller 的 AOP 中执行，验证码状态操作由 Service 调用专用组件。
- 当前为单实例 Redis；多 Key Lua 原子性依赖所有相关 Key 位于同一 Redis 实例，不宣称支持 Redis Cluster。
- 保持 Java 8、Spring Boot 2.3.12.RELEASE、MyBatis-Plus 3.4.3、spring-data-redis 2.6.2、Lettuce 6.1.6.RELEASE、Redisson 3.13.6；不升级依赖。
- 唯一计划新增的生产依赖是由现有 Boot 父工程管理版本的 `spring-boot-starter-aop`，不单独指定或升级版本；测试复用现有 JUnit 5、Mockito、Spring Test。
- 不修改 `seckill.lua`、`VoucherOrderServiceImpl`、原有库存判断、订单 ID 生成及异步下单逻辑；不修复无关业务。
- 按依赖顺序一次实现一个 Task，每项立即运行测试；用户已授权连续执行 Task 1～7，无需逐项询问，最后统一展示真实结果。
- 所有新增及实质修改的 Java 类、接口、枚举和手写 public 方法均须规范中文 JavaDoc；说明职责、参数、返回、异常及适用边界；复杂逻辑解释设计原因，Lombok 生成方法不逐一补注释。
- 不在日志中记录原始验证码、Token、完整手机号、请求体、短信厂商原始响应或可能包含上述数据的异常 message。
- 原设计阶段“仅生成文档”的限制已由用户最新实施授权替代；本轮不启动原主应用、不调用实际短信服务，只实施已确认范围。

## Review Focus

1. 已存在但无 TTL、错误类型或损坏字段的保护 Key：返回 503，不能永久 429 或部分写入；Task 1/4。
2. 伪造代理头、多值头和等价 IPv6：不能获得额外 IP 额度；Task 2。
3. 旧短信请求的失败补偿晚于新签发：不能误删新验证码或恢复配额；Task 4。
4. Token 续期异常发生在写入 ThreadLocal 前后：不残留身份、不复活已退出 Token，25 天毫秒转换不溢出；Task 6。
5. Redis 超时但脚本实际上已执行：安全拒绝、不调用短信、不自动执行第二次；Task 7。

---

## 执行规则、阶段与文件地图

本计划已经完成。本轮采用当前会话串行执行，按用户最新授权连续完成全部 Task，没有并行修改相互依赖的业务代码。实现、测试和检查的完成证据见前面的本轮实施记录。

| 阶段 | Task | 可独立验收的交付 |
| --- | --- | --- |
| 一：核心 | 1 | 真实 Redis 可验证的固定窗口 + 严格冷却引擎 |
| 二：接口接入 | 2、3 | HTTP/IP AOP，以及原秒杀 Controller 的多维准入 |
| 三：验证码闭环 | 4、5 | 发送/失败处理，以及原子校验/消费 |
| 四：会话与验收 | 6、7 | Token 退出、整体协议与故障回归 |

新增目录为 `src/main/java/com/hmdp/ratelimit`、`auth`、`exception` 及 `src/main/resources/lua/protection`；保留现有业务文件位置。以下 Task 的 Files 是唯一写入范围，新增需求先回到设计。

所有新增测试位于 `src/test/java/com/hmdp/protection/`，以 `Protection*Test` 命名；共用夹具在其 `support/` 子包。真实脚本测试禁止扫描主启动类/使用原 @SpringBootTest，避免启动数据库、Redisson、订单消费者或现有数据预热测试。

### 测试环境与报告

前置环境检查记录：当时仅核实 Maven/JDK 版本；本轮执行结果已补充在前面的实施记录。Maven 3.9.4 默认使用 JDK 17，已发现 `D:\JAVA\wjzjdk8`（1.8.0_341）可供本项目使用；不修改系统 JAVA_HOME。执行每个测试命令前，仅在当前 shell 中临时指定该 JDK，结束后恢复原值。

真实 Redis 测试本轮使用用户明确授权的只读 YAML 连接方式：显式提供 `-Dhmdp.test.redis.config=src/main/resources/application.yaml` 和 `-Dhmdp.test.redis.database=15`。不修改 YAML、不设置系统环境变量，拒绝测试库与业务库相同。原 HMDP_TEST_REDIS_* 方式作为可选兼容；两种方式都必须明确选择，不自动回退到业务配置。缺配置明确失败，不跳过、不自动安装软件。

每次运行展示：实际命令、JDK、测试数量、Failures/Errors/Skipped、退出码。以下 Expected 是将来执行时的验收标准，不是当前结果。`mvn -version` 伴随的 Access is denied. 需在执行 Task 1 时定位其是否影响命令；不凭版本输出声称构建成功。

每个 Task 按“先失败测试 → 确认真实失败原因 → 最小实现 → 通过测试 → 检查差异/JavaDoc → 按最新连续授权进入下一 Task”执行。计划中的 commit 步骤只提交该 Task 的明确文件，不使用 `git add .`；若沙箱不允许更新 Git 元数据，记录未提交而不是影响用户已有修改。

## Task 1：复合限流引擎与隔离 Redis 测试

**Files:**
- Create: `src/main/java/com/hmdp/config/ProtectionProperties.java`
- Create: `src/main/java/com/hmdp/utils/ProtectionKeys.java`
- Create: `src/main/java/com/hmdp/ratelimit/RateRule.java`
- Create: `src/main/java/com/hmdp/ratelimit/RateLimitDecision.java`
- Create: `src/main/java/com/hmdp/ratelimit/RedisRateLimiter.java`
- Create: `src/main/java/com/hmdp/exception/ProtectionRejectedException.java`
- Create: `src/main/java/com/hmdp/exception/ProtectionUnavailableException.java`
- Create: `src/main/java/com/hmdp/exception/ProtectionInvalidRequestException.java`
- Create: `src/main/resources/lua/protection/rate_limit.lua`
- Modify: `src/main/java/com/hmdp/utils/RedisConstants.java`
- 原计划追加 YAML 已按用户新要求取消；通过 `ProtectionProperties.java` 默认值提供配置，application.yaml 未修改。
- Test/Create: `src/test/java/com/hmdp/protection/support/RedisProtectionTestSupport.java`
- Test/Create: `src/test/java/com/hmdp/protection/support/LuaTimeHarness.java`
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionRateLimiterTest.java`

**Interfaces:**
- Consumes: 现有 StringRedisTemplate、RedisConstants；不依赖 MySQL 或订单 Service。
- Produces: `RateRule.fixedWindow(String key, long limit, long windowMs): RateRule`、`RateRule.cooldown(String key, long durationMs): RateRule`，Kind 为 FIXED_WINDOW/COOLDOWN。
- Produces: RateRule 只读访问器 `getKey(): String`、`getKind(): RateRule.Kind`、`getLimit(): long`、`getDurationMs(): long`；固定窗口的 duration 即 windowMs，冷却的 limit 固定为 1。
- Produces: `RateLimitDecision.isAllowed(): boolean`、`getRetryAfterMs(): long`；允许时 retry=0。
- Produces: `RedisRateLimiter.acquire(List<RateRule> rules): RateLimitDecision`，只支持 1..8 条且 Key 不重复，空/重复规则在执行前拒绝；故障抛 ProtectionUnavailableException。
- Produces: `ProtectionKeys(ProtectionProperties properties)`、`key(String basePrefix, String... segments): String`；拼接配置前缀、RedisConstants 前缀与受控维度，不打印 Key。
- Produces: `ProtectionRejectedException(long retryAfterMs)`、`ProtectionUnavailableException(String operationCode)`、`ProtectionInvalidRequestException()`；异常不包含敏感参数或原始 cause/message。
- Produces: `RedisProtectionTestSupport` 管理独立连接、唯一测试前缀、已注册 Key 清理；`LuaTimeHarness.evalAt(String resource, List<String> keys, List<String> args, long nowMs): LuaTimeHarness.Probe` 执行原脚本的 TIME 包装。内部静态测试 DTO Probe 提供 `getResult(): List<Long>`、`getValue(String key): Object`、`getPttlMs(String key): long`，返回同次 Lua 结果及类型对应的值/Hash/PTTL 快照，测试专用。

配置名使用 kebab-case，单位明确：`key-prefix=""`、`seckill-voucher-limit=100`、`seckill-voucher-window-ms=1000`、`seckill-user-limit=5`、`seckill-user-window-ms=10000`、`seckill-user-voucher-cooldown-ms=2000`、`sms-ip-limit=20`、`login-ip-limit=60`、`ip-window-ms=60000`、`sms-code-ttl-ms=120000`、`sms-cooldown-ms=60000`、`sms-hour-limit=5`、`sms-day-limit=10`、`verify-failure-limit=5`、`verify-failure-window-ms=900000`、`verify-lock-ms=900000`、`token-idle-ttl-minutes=36000`、`sms-mode=mock`、`trusted-proxy-addresses=[]`。启动时校验正数、受控前缀、模式；不新加校验依赖。

- [x] **Step 1：编写边界与并发失败测试。** 用独立状态与虚拟 Redis TIME 验证 `voucherAllows100Rejects101`（其他维度足额）、`voucherResetsAt1000Not999`、`userAllows5Rejects6AcrossVouchers`、`userResetsAt10000Not9999`、`pairRejects1999Allows2000AcrossFixedBoundary`、`rejectLeavesAllOtherValuesAndTtlsUnchanged`、`twoClientsShareQuota`。真实时间补充并发准入不超配额；固定窗口并发测试用不替换 TIME 的测试包装记录生产 Lua 实际读取的 TIME/windowId，发生跨窗则按各自 windowId 验证，不根据线程启动时间推断窗口，不断言跨窗总次数。

  断言契约示例（变量是夹具在对应时刻执行后的结果，不是本轮创建的测试代码）：

  ```java
  // pairRejects1999Allows2000AcrossFixedBoundary
  assertEquals(Long.valueOf(1L), at1999.getResult().get(0));
  assertEquals(Long.valueOf(0L), at2000.getResult().get(0));
  // rejectLeavesAllOtherValuesAndTtlsUnchanged：TTL 允许自然减少，但不增加
  assertEquals(beforeVoucherCount, afterVoucherCount);
  assertTrue(afterVoucherTtl <= beforeVoucherTtl);
  ```
- [x] **Step 2：补充异常/夹具断言。** WrongType、count 非数字/负数、缺字段、无 TTL、重复 Key、空规则、脚本空返回不放行；规则拒绝不续 TTL。测试配置缺失是环境失败；测试结束仅删除登记的本次前缀 Key。用原始脚本真实 TIME 覆盖 2000ms 冷却和 TTL，不用包装器假装物理过期。
- [x] **Step 3：运行确认失败。** Run: `mvn "-Dtest=ProtectionRateLimiterTest" test`。Expected: 新类型/资源缺失或断言失败；若只有环境/依赖失败，先报告并解决，不能算有效红灯。
- [x] **Step 4：实现类型、配置、Key 构造和异常。** 新前缀集中加入 RedisConstants；不改变已有常量含义。Hash 固定窗口包含 windowId/count；所有时间用 long，日偏移 28800000ms。
- [x] **Step 5：实现 rate_limit.lua 及 acquire 适配。** 用 Redis TIME；先验证全部状态/参数再判断，再统一写入。固定窗口根据 windowId 取当前 count；冷却依据 deadline 且新 TTL=2000ms。输出 `[0,0]` 允许、`[1,retryMs]` 拒绝、`[3,0]` 内部状态错误；适配器把错误/异常/空结果归为 503，不自动重试。内部状态异常先于写入，不做逐维脚本补偿。
- [x] **Step 6：重跑 Step 3 并审查。** Expected: Failures=0、Errors=0、Skipped=0、exit=0；边界包装器预置较长物理 TTL，短至 1ms 的写入在同次 Lua 内取快照。检查 JavaDoc 和 `git diff --check`；提交仅本 Task 文件，建议消息 `feat: add atomic Redis admission limiter`。
- [x] **Step 7：展示实际结果并停止。** 解释 Hash 的窗口号与组合冷却截止时间为何不同，等待下一 Task 授权。

## Task 2：AOP、IP 规则与 HTTP 异常协议

**Files:**
- Create: `src/main/java/com/hmdp/ratelimit/RateLimit.java`
- Create: `src/main/java/com/hmdp/ratelimit/RateLimitPolicy.java`
- Create: `src/main/java/com/hmdp/ratelimit/ClientIpResolver.java`
- Create: `src/main/java/com/hmdp/ratelimit/RateLimitPolicyResolver.java`
- Create: `src/main/java/com/hmdp/ratelimit/RateLimitAspect.java`
- Modify: `pom.xml`（仅新增 Boot 管理的 AOP Starter）
- Modify: `src/main/java/com/hmdp/controller/UserController.java`（仅两个入口注解及相应 JavaDoc）
- Modify: `src/main/java/com/hmdp/config/WebExceptionAdvice.java`
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionRateLimitAspectTest.java`

**Interfaces:**
- Consumes: Task 1 的配置、Key 构造、RateRule、RedisRateLimiter、三种异常。
- Produces: `@RateLimit(policy = RateLimitPolicy.XXX)`，METHOD/RUNTIME；enum 值 SMS_SEND_IP、LOGIN_IP、SECKILL。
- Produces: `ClientIpResolver.resolve(HttpServletRequest request): String`。
- Produces: `RateLimitPolicyResolver.resolve(RateLimitPolicy policy, Object[] args, HttpServletRequest request): List<RateRule>`。
- Produces: `RateLimitAspect.guard(ProceedingJoinPoint joinPoint, RateLimit annotation): Object throws Throwable`，同一调用只 acquire 一次、通过后 proceed 一次。
- Produces: 429/400/503 的 Result 协议，429 的 Retry-After 与 data.retryAfterSeconds；对外文案严格采用设计第 8 节。

- [x] **Step 1：编写失败的 AOP/MockMvc 测试。** `smsIpAllows20Rejects21`、`loginIpAllows60Rejects61`、`ipWindowResetsAt60000`（真实窗口算法复用 Task 1 夹具）、`deniedNeverInvokesUserService`、`redisFailureIs503NeverProceed`、`retryAfterRoundsUp`（1ms→1s，2001ms→3s）、`resultShapeUnchanged`。测试 Controller 必须是真实 Spring 代理，不能只直接调用切面。

  ```java
  // retryAfterRoundsUp：拒绝结果的 retryAfterMs=2001
  assertEquals(429, response.getStatus());
  assertEquals("3", response.getHeader("Retry-After"));
  verify(userService, never()).sendCode(anyString(), any());
  ```
- [x] **Step 2：编写可信 IP 输入测试。** `untrustedForwardedHeadersIgnored`、`trustedProxyAcceptsSingleLiteralXRealIp`、`invalidOrMultipleHeadersFallBackWithoutDns`、`equivalentIpv6SharesBucket`、`invalidPolicyArgumentsRejectBeforeRedis`；不接受客户端策略或 SpEL。Token 不参与 IP Key。
- [x] **Step 3：运行确认失败。** Run: `mvn "-Dtest=ProtectionRateLimitAspectTest" test`。Expected: AOP/策略类型缺失或明确行为断言失败。
- [x] **Step 4：实现注解、解析器和切面。** Resolver 从 UserHolder 获取用户 ID、从 args[0] 读取正数券 ID；SMS/LOGIN 仅生成各自一个 IP 固定窗口规则；SECKILL 生成三条规则供 Task 3 使用。默认不信任代理头；可信代理仅精确 IP 列表，不引入 CIDR 或网络解析库。
- [x] **Step 5：最小接入和异常映射。** 增加 AOP Starter，不指定版本；只给 sendCode/login 加相应注解。Advice 特别处理三种安全异常，保留 Result 与其他运行时异常行为，安全日志仅含固定操作码和异常类名。
- [x] **Step 6：验证。** Run: `mvn "-Dtest=ProtectionRateLimiterTest,ProtectionRateLimitAspectTest" test`。Expected: 全部通过，无跳过；核对 POM 没有升级，中文 JavaDoc 完整，`git diff --check`；明确文件提交，建议 `feat: apply annotation based IP rate limits`。
- [x] **Step 7：展示实际结果并停止。** 解释 AOP 在鉴权之后、可信代理边界和共享 IP 误伤风险。

## Task 3：秒杀 Controller 多维准入接入

**Files:**
- Modify: `src/main/java/com/hmdp/controller/VoucherOrderController.java`（只加注解及中文 JavaDoc）
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionSeckillRateLimitTest.java`

**Interfaces:**
- Consumes: Task 2 的 SECKILL 策略、真实 Controller 代理，原 `IVoucherOrderService.seckillVoucher(Long voucherId): Result`。
- Produces: 原 URL/参数/成功 Result 不变；进入 Controller 前完成一次 acquire，拒绝时 Service 调用次数为 0。

- [x] **Step 1：编写路由/代理失败测试。** `seckillUsesAuthenticatedUserAndPathVoucher` 捕获三条规则，断言窗口分别为 1000/10000ms、组合 Kind=COOLDOWN 且 duration=2000ms；`differentVouchersShareUserKeyButNotPairKey`；`differentTokensSameUserShareKeys`；`blockedDoesNotGenerateOrSubmitOrder`（verify 原 Service never）；`allowedDelegatesExactlyOnce`；`anonymousProtectedRequestIs401`；`invalidVoucherIs400BeforeRedis`。

  ```java
  // seckillUsesAuthenticatedUserAndPathVoucher
  assertEquals(1000L, voucherRule.getDurationMs());
  assertEquals(10000L, userRule.getDurationMs());
  assertEquals(RateRule.Kind.COOLDOWN, pairRule.getKind());
  assertEquals(2000L, pairRule.getDurationMs());
  ```
- [x] **Step 2：运行确认失败。** Run: `mvn "-Dtest=ProtectionSeckillRateLimitTest" test`。Expected: 当前 Controller 未标注导致保护缺失。
- [x] **Step 3：只增加 SECKILL 注解。** 不给 Service 再加注解，不修改库存 Lua、订单消费者或返回订单 ID 的时机；补充类及 public 方法中文 JavaDoc。
- [x] **Step 4：验证协议和范围。** Run: `mvn "-Dtest=ProtectionRateLimiterTest,ProtectionRateLimitAspectTest,ProtectionSeckillRateLimitTest" test`。Expected: 全部通过；记录任务前后 `VoucherOrderServiceImpl.java` 与 `seckill.lua` 的 SHA256，必须相同，不以工作区全量 git diff 误判用户既有改动；`git diff --check`。只提交 Controller 与本测试，建议 `feat: protect voucher seckill admission`。
- [x] **Step 5：展示实际结果并停止。** 明确每券总窗口、跨券用户窗口和用户/券严格冷却的差别。

## Task 4：验证码签发、短信结果与安全补偿

**Files:**
- Create: `src/main/java/com/hmdp/auth/SmsSender.java`
- Create: `src/main/java/com/hmdp/auth/SmsSendOutcome.java`
- Create: `src/main/java/com/hmdp/auth/MockSmsSender.java`
- Create: `src/main/java/com/hmdp/auth/SmsCodeService.java`
- Create: `src/main/resources/lua/protection/sms_reserve.lua`
- Create: `src/main/resources/lua/protection/sms_cleanup.lua`
- Modify: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`（仅发送方法及相关 JavaDoc/注入）
- 原计划追加 YAML 日志配置已取消；通过 `ProtectionProperties.java` 的启动初始化仅将 UserMapper 设为 INFO，application.yaml 未修改。
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionSmsSendTest.java`

**Interfaces:**
- Consumes: Task 1/2 的配置、Key、异常和 IP 入口保护。
- Produces: `SmsSendOutcome` 枚举 ACCEPTED、REJECTED、UNKNOWN；`SmsSender.send(String phone, String code, String issueId): SmsSendOutcome`。
- Produces: `SmsCodeService.sendCode(String phone): void`；正常结束表示通道接受，其他结果抛安全异常；原 IUserService.sendCode(String,HttpSession) 签名不变。
- Produces: 私有补偿以 issueId 为所有权，只有内部方法调用，不新增 HTTP API；SmsCodeService 的构造依赖为 StringRedisTemplate、ProtectionProperties、ProtectionKeys、SmsSender。

- [x] **Step 1：编写真实 Lua 签发边界测试。** `cooldownRejects59999Allows60000`、`hourAllows5Rejects6`、`dayAllows10Rejects11`、`hourResetsAtNextHour`、`dayResetsAtShanghaiMidnightNotUtcMidnight`、`lockedPhoneCannotSend`、`anyDenialLeavesOtherQuotasAndCodeUnchanged`、`resendKeepsFailureCount`。小时额度试验满足 60 秒冷却；日额度分散到多个小时，不能让别的规则遮蔽待测规则。
- [x] **Step 2：编写短信与日志失败测试。** ACCEPTED 得到成功且 code/issue 有相同过期时刻；REJECTED 删除本次 code/issue，但保持冷却、小时/日计数和 fail；UNKNOWN 保留 code/issue；旧 issue 的清理不删除新码；补偿 Redis 异常为 503；通道异常按 UNKNOWN；预占失败不调用 Sender。OutputCaptureExtension 断言测试验证码、完整手机号、Token 哨兵不出现在新增组件日志中。

  ```java
  // rejectedSendDeletesOnlyItsOwnCode：首次预占后通道明确拒绝
  assertNull(redis.opsForValue().get(codeKey));
  assertNull(redis.opsForValue().get(issueKey));
  assertEquals("1", redis.opsForHash().get(hourKey, "count"));
  assertTrue(redis.getExpire(cooldownKey, TimeUnit.MILLISECONDS) > 0);
  ```
- [x] **Step 3：运行确认失败。** Run: `mvn "-Dtest=ProtectionSmsSendTest" test`。Expected: 组件和脚本不存在或行为不满足。
- [x] **Step 4：实现两个脚本。** reserve KEYS 顺序为 code、issue、cooldown、hour、day、lock；ARGV 为 code、issueId、codeTtlMs、cooldownMs、hourLimit、dayLimit。根据 Redis TIME 计算小时和 UTC+8 日号，预查所有状态后原子签发；结果沿用 `[0,0]`/`[1,retryMs]`/`[3,0]`。cleanup KEYS 为 code、issue，ARGV 为 issueId；匹配则删除并返回 1，不匹配/已消费返回 0，不触碰其他状态。
- [x] **Step 5：实现 SmsCodeService.sendCode 与端口。** 用 SecureRandom 生成六位数字、随机 UUID 签发 ID；预占成功后只调用 Sender 一次。按设计第 6 节处理三种结果；不返额度、不恢复旧码、不自动重发。MockSmsSender 仅在 sms-mode=mock 时启用，未知模式启动失败，模拟结果不日志输出验证码。
- [x] **Step 6：接入原 sendCode。** 保留其公开签名及 Result.ok 行为；替换原直写 Redis 和原始验证码日志。通过 ProtectionProperties 的启动初始化仅将 UserMapper 日志设为 INFO，抑制手机号 SQL 参数，不修改用户 YAML logging 块。
- [x] **Step 7：验证。** Run: `mvn "-Dtest=ProtectionRateLimiterTest,ProtectionRateLimitAspectTest,ProtectionSmsSendTest" test`。Expected: 全部通过；检查本次码/旧码所有权竞争与正 TTL，JavaDoc 解释故障时保留额度的原因；明确文件提交，建议 `feat: guard SMS issuance and delivery failures`。
- [x] **Step 8：展示实际结果并停止。** 明确当前仍是短信模拟器，测试证明的是结果处理，不声称接通厂商或真实送达。

## Task 5：验证码原子比较、错误计数与一次性消费

**Files:**
- Create: `src/main/resources/lua/protection/sms_verify.lua`
- Modify: `src/main/java/com/hmdp/auth/SmsCodeService.java`
- Modify: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`（仅登录校验与安全数据库错误处理）
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionSmsVerifyTest.java`

**Interfaces:**
- Consumes: Task 4 的签发格式、code/issue 所有权，以及原 UserService 查询/注册流程。
- Produces: `SmsCodeService.verifyAndConsume(String phone, String code): boolean`；true 表示原子消费，false 统一代表错误/过期/已消费；锁定抛 ProtectionRejectedException，系统异常抛 ProtectionUnavailableException，格式异常抛 ProtectionInvalidRequestException。
- Produces: 原 login(LoginFormDTO,HttpSession) 签名不变；替换 Java GET/比较，只在 verify=true 后进入数据库与原 Token 创建逻辑，Task 6 再替换 Token 写入。

- [x] **Step 1：编写失败计数与锁定测试。** `fourFailuresDoNotLockFifthLocks`；第 5 次返回 429；`correctCodeStillRejectedWhileLocked`；`resendDoesNotResetFailures`；`denialDoesNotExtendFailureOrLockTtl`；`lockExpiryRestoresAttempt`（实际短 TTL 夹具到期，不仅跳虚拟时钟）；错误、过期、缺失、重放均为相同失败文案；无有效码时不创建 fail。
- [x] **Step 2：编写原子消费测试。** 原始脚本、真实 Redis 下，用屏障并发发起 50 次相同正确码校验，断言 `successCount == 1`，其余 49 次统一失败，code/issue/fail 均删除；线程池有界等待并 finally 关闭。另测重发与旧码校验竞争，以及 code/issue 不成对返回 503。

  ```java
  // concurrentSameCodeConsumedOnce
  assertEquals(1L, successCount);
  assertEquals(49L, failureCount);
  assertNull(redis.opsForValue().get(codeKey));
  assertNull(redis.opsForValue().get(issueKey));
  assertNull(redis.opsForValue().get(failKey));
  ```
- [x] **Step 3：编写业务与日志测试。** verify=false/锁定/Redis 故障时 mapper 不被调用；成功才进入查询/注册；数据库 RuntimeException 包含完整手机号哨兵时，对外及捕获日志均不包含原 message/cause，返回统一 503。不同注册状态对验证码错误使用同一文案。
- [x] **Step 4：运行确认失败。** Run: `mvn "-Dtest=ProtectionSmsVerifyTest" test`。Expected: 原子校验方法/脚本缺失或重复消费等明确断言失败。
- [x] **Step 5：实现 sms_verify.lua。** KEYS 为 code、issue、fail、lock；ARGV 为 inputCode、failureLimit=5、failureWindowMs=900000、lockMs=900000；先锁定后比较，错误首次设置 fail TTL，第 5 次创建 lock，匹配删除 code/issue/fail。返回 `[0,0]` 成功、`[2,0]` 普通失败、`[1,retryMs]` 锁定、`[3,0]` 状态错误。
- [x] **Step 6：接入 login 并最小化异常暴露。** 参数校验后只调用一次 verifyAndConsume；查询/注册代码块单独捕获 RuntimeException，记录固定操作码/异常类，不附原 message/cause，转成安全 503；不顺带重构用户表或处理原有并发注册策略。验证码消费后 DB 失败不恢复验证码。
- [x] **Step 7：验证。** Run: `mvn "-Dtest=ProtectionSmsSendTest,ProtectionSmsVerifyTest,ProtectionRateLimitAspectTest" test`。Expected: 全部通过；检查 JavaDoc 对“原子消费不覆盖 MySQL”的解释及敏感日志；明确文件提交，建议 `feat: atomically consume login verification codes`。
- [x] **Step 8：展示实际结果并停止。** 展示恰好一次成功的实际计数，不将测试结果扩大为跨系统事务保证。

## Task 6：Token 原子存储、续期与幂等退出

**Files:**
- Create: `src/main/java/com/hmdp/auth/TokenSessionService.java`
- Create: `src/main/resources/lua/protection/token_create.lua`
- Create: `src/main/resources/lua/protection/token_refresh.lua`
- Modify: `src/main/java/com/hmdp/service/IUserService.java`（新增退出签名及中文 JavaDoc）
- Modify: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`（只替换 Token 创建并新增退出）
- Modify: `src/main/java/com/hmdp/controller/UserController.java`（仅退出方法及中文 JavaDoc）
- Modify: `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`
- Modify: `src/main/java/com/hmdp/config/MvcConfig.java`
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionTokenSessionTest.java`

**Interfaces:**
- Consumes: ProtectionKeys、配置、UserDTO、原 UserHolder、LoginInterceptor。
- Produces: `TokenSessionService.create(UserDTO user): String`、`resolveAndRefresh(String token): UserDTO`（无效/不存在为 null）、`logout(String token): void`。
- Produces: `IUserService.logout(String token): Result`；Controller.logout 增加 `@RequestHeader(value="authorization", required=false) String token` 参数，原 POST /user/logout 不变。
- Produces: `RefreshTokenInterceptor(TokenSessionService tokenSessionService)`，MvcConfig 以该依赖构造拦截器；其余顺序保持 refresh=0、login=1。

- [x] **Step 1：编写存储与失效失败测试。** create 返回 32 位十六进制 Token，Hash 是 UserDTO 原结构，TTL 接近 `2160000000L` 且正数；refresh 延长有效期但不会创建缺失 Key；缺失/格式非法不访问 Redis。
- [x] **Step 2：编写真实最小 MVC 退出测试。** 手工创建 Redis Token，带原始 authorization 访问 /user/me 为 200；POST /user/logout 为 200；再访问 /user/me 为 401；缺失、非法、过期、重复退出成功；Redis DEL 故障为 503，不假报成功。另用屏障制造 refresh 与 logout 竞争，最终 Key 不存在。

  ```java
  // logoutInvalidatesOldTokenAndKeepsExistingTtlUnits
  assertEquals(2160000000L, TimeUnit.MINUTES.toMillis(36000L));
  assertEquals(200, logoutResponse.getStatus());
  assertEquals(401, afterLogoutResponse.getStatus());
  assertNull(tokenSessionService.resolveAndRefresh(oldToken));
  ```
- [x] **Step 3：编写 ThreadLocal/日志回归。** `refreshFailureLeavesNoUserHolder`、`requestCompletionClearsIdentity`；Redis 故障/续期异常不能残留用户供下一请求使用；Token 哨兵不出现在日志。测试 finally 清理 UserHolder。
- [x] **Step 4：运行确认失败。** Run: `mvn "-Dtest=ProtectionTokenSessionTest" test`。Expected: Token 组件不存在、logout 未完成或旧 Token 仍有效等断言失败。
- [x] **Step 5：实现 TokenSessionService 与两个脚本。** create KEYS 为 token Key，ARGV 为 ttlMs 和 UserDTO 字段名/值对，原子 Hash+PEXPIRE；refresh KEYS 为 token Key，ARGV 为 ttlMs，存在时返回 Hash 字段和值并续期，不存在返回空数组。create 返回 1，其余异常安全 503；TTL 换算必须用 long。
- [x] **Step 6：最小接入原路径。** login 用 create；logout DEL 当前 Key 并清理本线程；MvcConfig 仅增加 /user/logout 放行并更换 refresh 构造依赖。preHandle 开头清理残留、仅成功读取续期后 saveUser，异常主动 remove；afterCompletion 继续清理。保留现有 LoginInterceptor 与公开路径权限，不引入 Bearer/JWT。
- [x] **Step 7：验证。** Run: `mvn "-Dtest=ProtectionTokenSessionTest,ProtectionSmsVerifyTest,ProtectionRateLimitAspectTest,ProtectionSeckillRateLimitTest" test`。Expected: 全部通过；检查 25 天 TTL 未误变、所有设备退出未被引入、旧 Token 仅在新受保护请求上拒绝；明确文件提交，建议 `feat: invalidate Redis tokens on logout`。
- [x] **Step 8：展示实际结果并停止。** 展示登录态→退出→401 的真实状态链，解释已认证在途请求的边界。

## Task 7：模块协议、故障与日志综合回归

**Files:**
- Test/Create: `src/test/java/com/hmdp/protection/ProtectionContractTest.java`
- Modify: 仅本计划中的已有 protection 测试/夹具（发现生产缺陷时先定位并另列最小修正文件，不能扩大业务范围）
- Modify: 本计划（勾选已验证项并记录真实结果，不改写原验收标准）

**Interfaces:**
- Consumes: Task 1..6 已冻结的全部接口；真实最小 MVC 上下文 + Redis，数据库与短信保持替身。
- Produces: 模块范围的回归证据，不能替代原订单可靠性验证或宣称真实短信送达。

- [x] **Step 1：先写综合契约断言并运行确认其能发现缺陷。** 覆盖公开 IP 入口 429→冷却/配额 429→发送/校验→Token→退出；不同注册状态的失败文案一致；秒杀拒绝时原 Service 不调用；所有对外响应无 code/token/phone/原始 Key 泄漏。若已有行为使新测试直接通过，记录其为补充回归，不为了制造红灯篡改生产代码。
- [x] **Step 2：增加故障注入断言。** 连接到测试明确选定的未监听端口制造连接失败，不停止真实 Redis；测试适配器模拟“先执行真实 reserve Lua 再抛超时”，断言返回 503、Sender 未调用、脚本只执行一次、已生成状态按 TTL 保留。补充 SMS REJECTED/UNKNOWN、补偿 Redis 故障及全路径日志断言。仅测试替身注入，不向生产脚本/HTTP 暴露故障开关。

  ```java
  // reserveExecutedButReplyTimedOutDoesNotResend
  assertEquals(503, response.getStatus());
  assertEquals(1, scriptExecutionCount);
  verify(smsSender, never()).send(anyString(), anyString(), anyString());
  assertTrue(redis.getExpire(cooldownKey, TimeUnit.MILLISECONDS) > 0);
  ```
- [x] **Step 3：运行新综合测试。** Run: `mvn "-Dtest=ProtectionContractTest" test`。Expected: 所有新增契约断言通过；失败先定位，不能通过放宽断言、跳过或关闭保护来通过。
- [x] **Step 4：运行全部模块测试与已有纯 Java 测试。** Run: `mvn "-Dtest=Protection*Test,NormalTest" test`。Expected: Failures=0、Errors=0、Skipped=0、exit=0。不运行会写业务 Redis 的原 HmDianPingApplicationTests/RedissonTest，不把选定测试通过说成整个仓库全量通过。
- [x] **Step 5：核查范围、文档和资源清理。** `git diff --check`，比较任务开始前后原 seckill.lua、VoucherOrderServiceImpl、数据库脚本、依赖版本与用户配置快照；原秒杀逻辑必须不变。检查中文 JavaDoc、日志脱敏、线程池/连接关闭、所有新 Key 正 TTL、无 FLUSHDB、无全局 JAVA_HOME 修改。更新本计划的真实报告；明确文件提交，建议 `test: verify login protection contracts and failures`。
- [x] **Step 6：展示结果并停止。** 分别列出通过/未通过/未执行项；没有实际测量不写 QPS、拦截率或送达率。剩余既有订单问题单独说明，不擅自修复。

## 自审覆盖与当前验证记录

| 用户要求 | 对应 Task |
| --- | --- |
| 每券 1 秒、用户 10 秒、用户/券严格两秒、原子全有或全无 | 1、3 |
| AOP 自定义注解，验证码发送/校验 IP 规则，429 | 2 |
| 发送冷却、自然小时/每日额度、短信失败和 ownership | 4 |
| 失败计数/锁定、重发不清零、同码仅消费一次 | 4、5 |
| 单实例前提、统一 Key、正确 TTL、无敏感日志 | 1、2、4、5、6、7 |
| 退出失效及现有拦截器拒绝旧 Token | 6 |
| Redis 异常、短信失败、窗口与冷却边界 | 1..7 各自测试 + 7 综合回归 |
| 中文 JavaDoc、一次一个 Task、真实结果、依赖不升级 | 每个 Task 的共同验收 |

当前状态：Task 1..7 的实现和模块验收均完成。最终 84 项测试通过（52 项 Java/MockMvc/既有基础回归 + 32 项真实 Redis），失败 0、错误 0、跳过 0，Maven 退出码 0。既有会访问业务 Redis 的 HmDianPingApplicationTests/RedissonTest 未运行、未删除或禁用。Git 集成未执行，修改保留在原工作区。
