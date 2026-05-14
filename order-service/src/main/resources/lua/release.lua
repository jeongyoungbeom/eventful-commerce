-- KEYS[1] = stockKey
-- KEYS[2] = holdKey
-- KEYS[3] = holdCountKey
-- ARGV[1] = quantity

if redis.call('EXISTS', KEYS[2]) == 1 then
    local quantity = tonumber(ARGV[1] or '1')
    redis.call('DEL', KEYS[2])

    -- restore available stock
    redis.call('INCRBY', KEYS[1], quantity)

    -- decrease hold count
    redis.call('DECRBY', KEYS[3], quantity)

    return 1
end

return 0
