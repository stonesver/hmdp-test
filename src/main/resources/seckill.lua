-- 1.参数列表
-- 优惠券id
local voucherId = ARGV[1]

local userId = ARGV[2]

--数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'order:stock' .. voucherId

--业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey))<=0) then
    --库存不足返回1
    return 1
end
--判断用户是否重复下单(sismember 判断set集合中同一个orderKey的值是否存在userid)
if(redis.call('sismember',orderKey,userId)==1) then
    --存在，返回2
    return 2
end
--可以下单,扣库存
redis.call('incrby',stockKey,-1)
redis.call('sadd',orderKey,userId)
return 0