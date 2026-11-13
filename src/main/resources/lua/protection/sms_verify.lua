-- 比对、计错、锁定、成功删除在单实例 Redis 中串行完成，不让同一码被并发消费。
if #KEYS~=4 or #ARGV~=4 then return {3,0} end
local limit,failTtl,lockTtl=tonumber(ARGV[2]),tonumber(ARGV[3]),tonumber(ARGV[4])
local function integer(n) return n and n>=0 and n==math.floor(n) and n<9007199254740991 end
if not integer(limit) or limit<1 or limit>1000000 or not integer(failTtl) or failTtl<1 or failTtl>86400000
    or not integer(lockTtl) or lockTtl<1 or lockTtl>86400000 or not string.match(ARGV[1],'^%d%d%d%d%d%d$') then return {3,0} end
local types={}
local seen={}
for i,k in ipairs(KEYS) do
    if seen[k] then return {3,0} end seen[k]=true
    types[i]=redis.call('TYPE',k).ok
    if types[i]~='none' and (types[i]~='string' or redis.call('PTTL',k)<0) then return {3,0} end
end
if (types[1]=='none') ~= (types[2]=='none') then return {3,0} end
if types[1]=='string' and not string.match(redis.call('GET',KEYS[1]),'^%d%d%d%d%d%d$') then return {3,0} end
if types[2]=='string' then
    local issue=redis.call('GET',KEYS[2])
    if #issue<1 or #issue>64 or not string.match(issue,'^[%w_-]+$') then return {3,0} end
end
if types[4]=='string' and redis.call('GET',KEYS[4])~='1' then return {3,0} end
local count=0
if types[3]=='string' then
    count=tonumber(redis.call('GET',KEYS[3]))
    if not integer(count) or count>1000000 then return {3,0} end
end
-- 锁定先于验证码比较；正确码也不能绕过，拒绝不能续锁。
if types[4]=='string' then return {1,math.max(1,redis.call('PTTL',KEYS[4]))} end
if types[1]=='none' then return {2,0} end
local code=redis.call('GET',KEYS[1])
if not string.match(code,'^%d%d%d%d%d%d$') then return {3,0} end
if code==ARGV[1] then
    redis.call('DEL',KEYS[1],KEYS[2],KEYS[3])
    return {0,0}
end
if types[3]=='none' then redis.call('SET',KEYS[3],'1','PX',failTtl)
else redis.call('INCR',KEYS[3]) end
if count+1>=limit then
    redis.call('SET',KEYS[4],'1','PX',lockTtl)
    return {1,lockTtl}
end
return {2,0}
