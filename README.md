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
| 日期、人数、取消和核销规则 | `BookingRules` | 业务 Service 在服务端校验 |
| 每小时补齐未来七天场次 | `VenueSlotScheduler` | `VenueServiceImpl.ensureRollingSlots` |


