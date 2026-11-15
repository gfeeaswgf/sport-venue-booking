local key = KEYS[1]
local action = ARGV[1]
local fingerprint = ARGV[2]
local owner = ARGV[3]
local state = redis.call('HGET', key, 'state')

if action == 'begin' then
    if not state then
        redis.call('HMSET', key, 'state', 'PROCESSING', 'fingerprint', fingerprint, 'owner', owner)
        redis.call('EXPIRE', key, tonumber(ARGV[4]))
        return {'ACQUIRED'}
    end
    if redis.call('HGET', key, 'fingerprint') ~= fingerprint then
        return {'CONFLICT'}
    end
    if state == 'SUCCESS' then
        return {'SUCCESS', redis.call('HGET', key, 'orderId')}
    end
    return {'PROCESSING'}
end

-- 过期的旧执行者不能覆盖或删除新的占位，也不能删除已完成的结果。
if state ~= 'PROCESSING'
        or redis.call('HGET', key, 'owner') ~= owner
        or redis.call('HGET', key, 'fingerprint') ~= fingerprint then
    return {'IGNORED'}
end

if action == 'complete' then
    redis.call('HMSET', key, 'state', 'SUCCESS', 'orderId', ARGV[6])
    redis.call('HDEL', key, 'owner')
    redis.call('EXPIRE', key, tonumber(ARGV[5]))
    return {'OK'}
end
if action == 'release' then
    redis.call('DEL', key)
    return {'OK'}
end
return {'IGNORED'}
