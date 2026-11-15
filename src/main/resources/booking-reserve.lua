-- 只在持有场馆/场地/场次数据库锁并完成当日订单校验后调用。
-- KEYS: 剩余名额、本用户当日标记；ARGV: 锁内剩余名额、订单ID、pending秒数、名额TTL。
local remaining = tonumber(ARGV[1])
local marker = redis.call('get', KEYS[2])
if marker and string.sub(marker, 1, 2) == 'P:' then
    return 2
end
-- 用当前读校准，Redis 重启、过期或取消回调延迟均不能永久挡住有余量的场次。
redis.call('set', KEYS[1], tostring(math.max(0, remaining)), 'EX', ARGV[4])
if remaining <= 0 then
    return 1
end
-- 已通过 MySQL 当日无订单校验，旧 confirmed 标记可能来自延迟的取消回调。
redis.call('set', KEYS[2], 'P:' .. ARGV[2], 'EX', ARGV[3])
redis.call('decr', KEYS[1])
return 0
