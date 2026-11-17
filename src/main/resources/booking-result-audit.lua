-- KEYS[1]: 固定审计Key。ARGV: JSON、固定截止毫秒、当前/前一/后一整秒、纳秒、精确用户ID。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local deadline = tonumber(ARGV[2])
if now >= deadline then
    return 0
end

local existing = redis.call('GET', KEYS[1])
if existing then
    local ok, old = pcall(cjson.decode, existing)
    local expected = cjson.decode(ARGV[1])
    if not ok or type(old) ~= 'table' then
        return -1
    end
    local fields = {'requestId', 'operation', 'targetType', 'targetId', 'outcome', 'executionType'}
    for _, field in ipairs(fields) do
        if old[field] ~= expected[field] then
            return -1
        end
    end
    -- 避免Lua double转换大整数后把相邻用户编号误判相等。
    local userId = string.match(existing, '"userId"%s*:%s*(%d+)')
    if userId ~= ARGV[7] or type(old.createTime) ~= 'string' then
        return -1
    end
    local created = old.createTime
    -- LocalDateTime.toString在整分钟会省略秒，兼容原版和持久秒精度快照。
    if #created == 16 then
        created = created .. ':00'
    end
    local base, fraction = string.match(created, '^(%d%d%d%d%-%d%d%-%d%dT%d%d:%d%d:%d%d)(.*)$')
    if not base or (fraction ~= '' and not string.match(fraction, '^%.%d+$')) or #fraction > 10 then
        return -1
    end
    local nanos = 0
    if fraction ~= '' then
        local digits = string.sub(fraction, 2)
        nanos = tonumber(digits .. string.rep('0', 9 - #digits))
    end
    local delta
    if base == ARGV[3] then
        delta = nanos - tonumber(ARGV[6])
    elseif base == ARGV[4] then
        delta = nanos - tonumber(ARGV[6]) - 1000000000
    elseif base == ARGV[5] then
        delta = nanos - tonumber(ARGV[6]) + 1000000000
    else
        return -1
    end
    if math.abs(delta) > 1000000000 then
        return -1
    end
    -- 已存在且相同的旧审计不覆盖、不续期，仅在必要时缩短到固定截止。
    local ttl = redis.call('PTTL', KEYS[1])
    if ttl < 0 or now + ttl > deadline then
        redis.call('PEXPIREAT', KEYS[1], ARGV[2])
    end
    return 2
end

redis.call('SET', KEYS[1], ARGV[1])
redis.call('PEXPIREAT', KEYS[1], ARGV[2])
return 1
