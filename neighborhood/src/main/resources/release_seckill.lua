-- Release a reservation only once, so duplicate compensation cannot inflate stock.
local stockKey = 'seckill:stock:' .. ARGV[1]
local orderKey = 'seckill:order:' .. ARGV[1]
if redis.call('srem', orderKey, ARGV[2]) == 1 then
    redis.call('incrby', stockKey, 1)
    return 1
end
return 0
