-- KEYS[1] = holdKey
-- KEYS[2] = holdCountKey
-- ARGV[1] = quantity

if redis.call('EXISTS', KEYS[1]) == 1 then
    local quantity = tonumber(ARGV[1] or '1')
    redis.call('DEL', KEYS[1])
    redis.call('DECRBY', KEYS[2], quantity)
    return 1
end

return 0
