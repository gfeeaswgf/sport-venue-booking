-- 单独的异步准入Key，不使用原SQL锁内会覆盖余量的脚本。
-- KEYS: stock,user; ARGV: action,requestId,SQL余量,pendingTTL,stockTTL。
local marker = redis.call('GET', KEYS[2])
if ARGV[1] == 'reserve' then
    if marker then
        return 2
    end
    if redis.call('EXISTS', KEYS[1]) == 0 then
        redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[5])
    end
    if tonumber(redis.call('GET', KEYS[1])) <= 0 then
        return 1
    end
    redis.call('DECR', KEYS[1])
    redis.call('SET', KEYS[2], 'P:' .. ARGV[2], 'EX', ARGV[4])
    return 0
end
if ARGV[1] == 'confirm' then
    if marker == 'P:' .. ARGV[2] then
        redis.call('SET', KEYS[2], 'C:' .. ARGV[2], 'EX', ARGV[5])
    end
else
    if marker == 'P:' .. ARGV[2] or marker == 'C:' .. ARGV[2] then
        redis.call('DEL', KEYS[2])
    end
    -- 不INCR，避免重建/重复回调将余量加多；下次从订单及持久化待处理记录恢复。
    redis.call('DEL', KEYS[1])
end
return 0
