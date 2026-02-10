-- KEYS[1] = stockKey         (e.g. stock:default)
-- KEYS[2] = holdKey          (e.g. hold:<uuid>)
-- KEYS[3] = holdCountKey     (e.g. holdCount:default)
-- ARGV[1] = ttlSeconds
-- ARGV[2] = holdValue (json)

local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
  return 0
end

-- decrease available stock
redis.call('DECR', KEYS[1])

-- create hold with TTL
redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[1])

-- increase hold count
redis.call('INCR', KEYS[3])

return 1
