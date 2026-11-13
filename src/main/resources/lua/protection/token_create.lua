-- Hash 和 TTL 同次建立；碰撞时拒绝覆盖其他登录凭证。
if #KEYS~=1 or #ARGV<3 or (#ARGV-1)%2~=0 then return -1 end
local ttl=tonumber(ARGV[1])
if not ttl or ttl<1 or ttl>31536000000 or ttl~=math.floor(ttl) then return -1 end
if redis.call('EXISTS',KEYS[1])~=0 then return -1 end
local fields={}
for i=2,#ARGV,2 do
    local field=ARGV[i]
    if fields[field] or (field~='id' and field~='nickName' and field~='icon') then return -1 end
    fields[field]=ARGV[i+1]
end
if not fields.id or not string.match(fields.id,'^[1-9]%d*$') or #fields.id>19 then return -1 end
-- Lua double 不能精确表示 Long 上界，等长十进制字符串比较避免精度丢失。
if #fields.id==19 and fields.id>'9223372036854775807' then return -1 end
for i=2,#ARGV,2 do redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]) end
redis.call('PEXPIRE',KEYS[1],ttl)
return 1
