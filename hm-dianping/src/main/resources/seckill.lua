--参数列表
--1.1 优惠券id
local voucherId = ARGV[1]
--1.2 用户id
local userId = ARGV[2]

--2.数据key
--2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
--2.2订单key
local orderKey = 'seckill:order:' .. voucherId

--3脚本业务
--3.1判断库存是否充足
local stock = tonumber(redis.call('get', stockKey) or '0')
if (stock <= 0) then
    --3。2库存不足
    return 1
end

--3.2判断用户是否下单
if(redis.call('sismember',orderKey,userId) == 1)then
    --3.3存在，则说明是重复下单
    return 2
end

--库存充足且第一次下单
--3.4扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
--3.5下单
redis.call('sadd',orderKey,userId)

return 0
