-- KEYS[1] = holdKey
-- KEYS[2] = holdCountKey

if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('DEL', KEYS[1])
    redis.call('DECR', KEYS[2])
    return 1
end

return 0
