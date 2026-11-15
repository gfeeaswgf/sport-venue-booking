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

