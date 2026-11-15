-- 以两个独立的一秒许可实现每用户任意连续一秒最多两次。
-- 每个许可的有效期从 Redis 执行 SET PX 开始，不受客户端停顿或时钟偏差影响。
-- 同时兼容不支持脚本命令复制的旧版 Redis，无需在 Lua 中调用 TIME。
local permits = {}
for i, key in ipairs(KEYS) do
    local available = nil
    for number = 1, tonumber(ARGV[2 + i]) do
        local permit = key .. ':' .. number
        if redis.call('EXISTS', permit) == 0 then
            available = permit
            break
        end
    end
    if not available then
        return 1
    end
    permits[i] = available
end

-- 全部维度有空位才占用，不会因场次限流拒绝而额外消耗用户额度。
for i, permit in ipairs(permits) do
    redis.call('SET', permit, ARGV[2], 'PX', tonumber(ARGV[1]))
end
return 0
