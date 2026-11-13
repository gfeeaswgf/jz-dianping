-- 先预查全部状态，再同时扣配额与签发。失败请求不刷新任何保护期限。
if #KEYS~=6 or #ARGV~=6 then return {3,0} end
local code,issue=ARGV[1],ARGV[2]
local codeTtl,cooldown,hourLimit,dayLimit=tonumber(ARGV[3]),tonumber(ARGV[4]),tonumber(ARGV[5]),tonumber(ARGV[6])
local function integer(n) return n and n>=0 and n==math.floor(n) and n<9007199254740991 end
if not string.match(code,'^%d%d%d%d%d%d$') or #issue<1 or #issue>64 or not string.match(issue,'^[%w_-]+$') then return {3,0} end
for _,n in ipairs({codeTtl,cooldown,hourLimit,dayLimit}) do if not integer(n) or n<1 then return {3,0} end end
if not codeTtl or not cooldown or not hourLimit or not dayLimit or codeTtl>86400000 or cooldown>86400000 or hourLimit>1000000 or dayLimit>1000000 then return {3,0} end
local expected={'string','string','string','hash','hash','string'}
local types={}
local seen={}
for i,k in ipairs(KEYS) do
    if seen[k] then return {3,0} end seen[k]=true
    types[i]=redis.call('TYPE',k).ok
    if types[i]~='none' and (types[i]~=expected[i] or redis.call('PTTL',k)<0) then return {3,0} end
end
if (types[1]=='none') ~= (types[2]=='none') then return {3,0} end
-- 类型和 TTL 正确仍可能存在损坏值；必须在任何拒绝或扣额之前检查内容。
if types[1]=='string' and not string.match(redis.call('GET',KEYS[1]),'^%d%d%d%d%d%d$') then return {3,0} end
if types[2]=='string' then
    local previousIssue=redis.call('GET',KEYS[2])
    if #previousIssue<1 or #previousIssue>64 or not string.match(previousIssue,'^[%w_-]+$') then return {3,0} end
end
if types[6]=='string' and redis.call('GET',KEYS[6])~='1' then return {3,0} end
local stamp=redis.call('TIME')
local now=tonumber(stamp[1])*1000+math.floor(tonumber(stamp[2])/1000)
local retry=0
if types[6]~='none' then retry=math.max(1,redis.call('PTTL',KEYS[6])) end
if types[3]~='none' then
    local deadline=tonumber(redis.call('GET',KEYS[3]))
    if not integer(deadline) then return {3,0} end
    retry=math.max(retry,deadline-now)
end
local windows={math.floor(now/3600000),math.floor((now+28800000)/86400000)}
local remain={3600000-now%3600000,86400000-(now+28800000)%86400000}
local limits={hourLimit,dayLimit}
local counts={0,0}
local oldWindows={}
for n=1,2 do
    local i=n+3
    if types[i]=='hash' then
        local fields=redis.call('HMGET',KEYS[i],'windowId','count')
        local window,count=tonumber(fields[1]),tonumber(fields[2])
        if not integer(window) or not integer(count) or window>windows[n] then return {3,0} end
        oldWindows[n]=window
        if window==windows[n] then counts[n]=count end
    end
    if counts[n]>=limits[n] then retry=math.max(retry,remain[n]) end
end
if retry>0 then return {1,retry} end
for n=1,2 do
    local k=KEYS[n+3]
    redis.call('HSET',k,'windowId',windows[n],'count',counts[n]+1)
    if oldWindows[n]~=windows[n] then redis.call('PEXPIRE',k,remain[n]) end
end
-- 同一脚本内 Redis 过期时钟固定，两个相同 PX 的 Key 具有同一到期时刻。
redis.call('SET',KEYS[1],code,'PX',codeTtl)
redis.call('SET',KEYS[2],issue,'PX',codeTtl)
redis.call('SET',KEYS[3],tostring(now+cooldown),'PX',cooldown)
return {0,0}
