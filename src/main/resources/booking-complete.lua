-- 标记绑定订单，旧订单取消或回滚不能清除后来订单的标记。
-- KEYS: 剩余名额、本用户当日标记；ARGV: confirm/rollback/cancel、订单ID、确认后TTL。
local marker = redis.call('get', KEYS[2])
if ARGV[1] == 'confirm' then
    if marker == 'P:' .. ARGV[2] then
        redis.call('set', KEYS[2], 'C:' .. ARGV[2], 'EX', ARGV[3])
    end
else
    if marker == 'P:' .. ARGV[2] or marker == 'C:' .. ARGV[2] then
        redis.call('del', KEYS[2])
    end
    -- 不盲目 INCR，防止过期重建/重复补偿把名额加多；下一次在数据库锁内恢复。
    redis.call('del', KEYS[1])
end
return 0
