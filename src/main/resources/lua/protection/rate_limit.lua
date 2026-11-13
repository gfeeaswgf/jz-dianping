-- 单实例 Redis：先校验/判断所有维度，再写入。Lua 保证不被其他请求插入，但不提供错误回滚。
if #KEYS < 1 or #KEYS > 8 or #ARGV ~= #KEYS * 3 then return {3,0} end
local stamp = redis.call('TIME')
local now = tonumber(stamp[1])*1000 + math.floor(tonumber(stamp[2])/1000)
local states = {}
local retry = 0
local seen = {}
local function integer(n) return n and n >= 0 and n == math.floor(n) and n < 9007199254740991 end
for i,key in ipairs(KEYS) do
    if seen[key] then return {3,0} end
    seen[key] = true
    local kind = ARGV[(i-1)*3+1]
    local limit = tonumber(ARGV[(i-1)*3+2])
    local duration = tonumber(ARGV[(i-1)*3+3])
    if not integer(limit) or limit < 1 or limit > 1000000 or not integer(duration) or duration < 1 or duration > 86400000 then return {3,0} end
    local t = redis.call('TYPE',key).ok
    if t ~= 'none' and redis.call('PTTL',key) < 0 then return {3,0} end
    if kind == 'FIXED_WINDOW' then
        if t ~= 'none' and t ~= 'hash' then return {3,0} end
        local window = math.floor(now/duration)
        local count = 0
        if t == 'hash' then
            local old = redis.call('HMGET',key,'windowId','count')
            local oldWindow,oldCount = tonumber(old[1]),tonumber(old[2])
            if not integer(oldWindow) or not integer(oldCount) or oldWindow > window then return {3,0} end
            if oldWindow == window then count=oldCount end
        end
        local remaining=duration-now%duration
        if count >= limit then retry=math.max(retry,remaining) end
        states[i]={kind,window,count,remaining,t}
    elseif kind == 'COOLDOWN' then
        if limit ~= 1 or (t ~= 'none' and t ~= 'string') then return {3,0} end
        if t == 'string' then
            local deadline=tonumber(redis.call('GET',key))
            if not integer(deadline) then return {3,0} end
            retry=math.max(retry,deadline-now)
        end
        states[i]={kind,now+duration,duration}
    else return {3,0} end
end
if retry > 0 then return {1,retry} end
for i,key in ipairs(KEYS) do
    local s=states[i]
    if s[1]=='FIXED_WINDOW' then
        -- 窗口号改变时重置；同窗只加次数，不因请求重新延长 TTL。
        local oldWindow = s[5]=='hash' and tonumber(redis.call('HGET',key,'windowId')) or nil
        redis.call('HSET',key,'windowId',s[2],'count',s[3]+1)
        if oldWindow ~= s[2] then redis.call('PEXPIRE',key,s[4]) end
    else
        -- 冷却从此次放行起算，跨越整秒/固定窗口仍不可提前获得额度。
        redis.call('SET',key,tostring(s[2]),'PX',s[3])
    end
end
return {0,0}
