# 体育场馆预约与管理平台

基于现有 Spring Boot、MyBatis-Plus、MySQL 和 Redis 工程改造。当前代码只包含账号和体育预约业务。


| 功能 | 入口 | 核心实现 |
| --- | --- | --- |
| 验证码登录、注册、本人资料 | `UserController` | `UserServiceImpl`、`UserInfoServiceImpl` |
| 场馆、场地、日期场次 | `VenueController` | `VenueServiceImpl` |
| 预约、取消、入场凭证 | `BookingOrderController` | `BookingOrderServiceImpl` |
| 场馆管理、发布场次、核销 | `AdminController` | 上述两个业务 Service |
| 登录与管理权限 | `MvcConfig` | `RefreshTokenInterceptor`、`LoginInterceptor`、`BookingAdminAccess` |
| 查询缓存与预约 Redis 状态 | `BookingCache`、`BookingRedisGuard` | 两份 `booking-*.lua`、`BookingTransactions` |
| 创建预约方法耗时 | `annotation/ApiTiming` | `aspect/ApiTimingAspect`，后台单行日志 |
| 每用户每秒两次请求、三天幂等 | `annotation/RateLimit`、`annotation/Idempotent` | 对应切面、`utils/BookingRequestRedis` 和 



2.0 新增AOP相关功能

限流 @RateLimit
同一用户每秒最多发送两次预约请求，减少频繁点击或脚本刷请求带来的压力。
例如： 一秒连续点击五次，最多放行两次，其余提示请求过于频繁。

请求幂等 @Idempotent
通过“用户＋请求标识”识别重复提交，成功结果在 Redis 保存三天，避免同一次操作重复创建订单。
例如： 后台预约成功，但手机网络超时没收到响应；用户沿用原请求标识重试，会拿到原订单，不再扣减名额。首次请求尚未完成时，则提示“正在处理”。

操作审计 @OperationLog
将操作人、操作对象、结果、耗时和请求标识存入 Redis，保留三天，方便排查问题。
例如： 学生反映订单被取消，可以查看记录，核对哪个账号何时发起取消，以及操作是否成功。

3.0 新增RocketMQ相关功能
用户提交预约先返回“正在处理”，后台消费者再异步创建订单，成功后页面才提示“预约成功”。系统通过持久化请求、失败重试和幂等校验，避免请求丢失或重复建单，让高并发时请求能更快得到受理。

| 场景 | 原同步返回成功 | 新异步返回受理 | 新异步查询确认成功 |
|---|---:|---:|---:|
| 20次顺序 | 26.319ms | 31.253ms | 798.640ms |
| 100人并发 | 754.078ms | 227.590ms | 1620.734ms |

3.1 原：订单消费者既要创建订单，又要完成审计和统计；
    现：只负责核心建单，剩余任务通过 RocketMQ 独立消费

| 场景 | 优化前平均耗时 | 优化后 | 降幅 |
|---|---:|---:|---:|
| 同场次竞争 | 13.88ms | 8.92ms | **35.7%** |
| 两个场次并发 | 10.34ms | 6.32ms | **38.9%** |
同场次竞争：100 人同时预约游泳馆的同一个日期、同一个时段。大家争抢同一份名额，数据库需要修改同一条场次库存记录，更容易发生等待。
两个场次并发：100 人分成两组，每组 50 人，分别预约游泳馆两个不同场次。两组修改不同的场次库存记录，竞争相对分散。