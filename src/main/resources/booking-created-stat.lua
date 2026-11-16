local value = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
local observed = tonumber(ARGV[2])
-- SQL查询可能先后交错，旧查询不得覆盖新的累计创建次数。
if observed >= value then
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
end
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 0
