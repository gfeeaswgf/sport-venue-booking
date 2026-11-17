-- SQL COUNT是事实来源；重复/乱序观测只能维持或提高历史创建数。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now >= tonumber(ARGV[3]) then
    return 0
end
local current = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
local observed = tonumber(ARGV[2])
if observed >= current then
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
end
redis.call('PEXPIREAT', KEYS[1], ARGV[3])
return 1
