-- 只读已有 Hash 并续期，退出 DEL 后不会因为刷新请求而重新创建凭证。
if #KEYS~=1 or #ARGV~=1 then return {'__PROTECTION_ERROR__'} end
local ttl=tonumber(ARGV[1])
if not ttl or ttl<1 or ttl>31536000000 or ttl~=math.floor(ttl) then return {'__PROTECTION_ERROR__'} end
local t=redis.call('TYPE',KEYS[1]).ok
if t=='none' then return {} end
if t~='hash' or redis.call('PTTL',KEYS[1])<0 then return {'__PROTECTION_ERROR__'} end
local data=redis.call('HGETALL',KEYS[1])
local found=false
for i=1,#data,2 do
    if data[i]=='id' then
        if not string.match(data[i+1],'^[1-9]%d*$') or #data[i+1]>19 then return {'__PROTECTION_ERROR__'} end
        if #data[i+1]==19 and data[i+1]>'9223372036854775807' then return {'__PROTECTION_ERROR__'} end
        found=true
    elseif data[i]~='nickName' and data[i]~='icon' then return {'__PROTECTION_ERROR__'} end
end
if not found then return {'__PROTECTION_ERROR__'} end
redis.call('PEXPIRE',KEYS[1],ttl)
return data
