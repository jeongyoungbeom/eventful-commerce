-- KEYS[1] = stockKey         (e.g. stock:default)
-- KEYS[2] = holdKey          (e.g. hold:<uuid>)
-- KEYS[3] = holdCountKey     (e.g. holdCount:default)
-- ARGV[1] = holdValue (json)
-- ARGV[2] = quantity

local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
local quantity = tonumber(ARGV[2] or '1')
if stock < quantity then
  return 0
end

-- decrease available stock
redis.call('DECRBY', KEYS[1], quantity)

-- create hold without TTL; order expiration/cancel flow releases it explicitly
redis.call('SET', KEYS[2], ARGV[1])

-- increase hold count
redis.call('INCRBY', KEYS[3], quantity)

return 1
