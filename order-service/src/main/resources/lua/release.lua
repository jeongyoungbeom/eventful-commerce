-- KEYS[1] = stockKey
-- KEYS[2] = holdKey
-- KEYS[3] = holdCountKey

if redis.call('EXISTS', KEYS[2]) == 1 then
    redis.call('DEL', KEYS[2])

    -- restore available stock
    redis.call('INCR', KEYS[1])

    -- decrease hold count
    redis.call('DECR', KEYS[3])

    return 1
end

return 0
