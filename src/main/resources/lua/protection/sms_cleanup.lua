-- 确定发送失败才调用。只清理自己持有的签发，不退额度、不解冷却。
if #KEYS~=2 or #ARGV~=1 or KEYS[1]==KEYS[2] then return -1 end
local t=redis.call('TYPE',KEYS[2]).ok
if t=='none' then return 0 end
if t~='string' or redis.call('PTTL',KEYS[2])<0 then return -1 end
if redis.call('GET',KEYS[2])~=ARGV[1] then return 0 end
if redis.call('TYPE',KEYS[1]).ok~='string' or redis.call('PTTL',KEYS[1])<0 then return -1 end
redis.call('DEL',KEYS[1],KEYS[2])
return 1
