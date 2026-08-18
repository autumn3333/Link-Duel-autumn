-- 匹配脚本:入队 + 原子取出一对
-- KEYS[1] = match:queue(ZSET,member=userId,score=入队时间戳)
-- KEYS[2] = 在线状态 key 前缀 "user:online:"
-- ARGV[1] = 当前加入者 userId
-- ARGV[2] = 入队时间戳(epoch ms)
--
-- 返回 nil(仍在排队)或 {userId1, userId2}(配对成功)
--
-- 为什么用 Lua:ZPOPMIN 一次取两人必须原子,否则并发加入时可能双重配对;
-- 崩溃残留的队列条目通过在线状态过滤并丢弃,不会永久堵住队头。

redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])

local alive = {}
if redis.call('ZCARD', KEYS[1]) >= 2 then
    local p = redis.call('ZPOPMIN', KEYS[1], 2)
    for _, e in ipairs(p) do
        if redis.call('EXISTS', KEYS[2] .. e[1]) == 1 then
            alive[#alive + 1] = e
        end
    end
    if #alive == 1 then
        -- 只剩一个人:按原时间戳放回,保持先到先配
        redis.call('ZADD', KEYS[1], alive[1][2], alive[1][1])
        return nil
    end
end

if #alive == 2 then
    return {alive[1][1], alive[2][1]}
end
return nil
