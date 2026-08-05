-- KEYS[1] = stockKey
-- KEYS[2] = holdKey
-- KEYS[3] = holdCountKey
-- KEYS[4] = expirationKey (sorted set)
-- ARGV[1] = orderId
-- ARGV[2] = productId
-- ARGV[3] = reservationId
-- ARGV[4] = quantity
-- ARGV[5] = expiresAtEpochMilli

local quantity = tonumber(ARGV[4])
local expiresAt = tonumber(ARGV[5])
if quantity == nil or quantity <= 0 or expiresAt == nil or expiresAt <= 0 then
    return -2
end

-- A reservation key is retained as a terminal tombstone after commit/release.
if redis.call('EXISTS', KEYS[2]) == 1 then
    local holdType = redis.call('TYPE', KEYS[2]).ok
    if holdType == 'string' then
        -- Compatibility with legacy holds created before metadata hashes.
        return 2
    end
    if holdType ~= 'hash' then
        return -2
    end

    local status = redis.call('HGET', KEYS[2], 'status') or 'RESERVED'
    local existingOrderId = redis.call('HGET', KEYS[2], 'orderId')
    local existingProductId = redis.call('HGET', KEYS[2], 'productId')
    local existingReservationId = redis.call('HGET', KEYS[2], 'reservationId')
    local existingQuantity = tonumber(redis.call('HGET', KEYS[2], 'quantity'))

    if status == 'COMMITTED' then
        return 3
    end
    if status == 'RELEASED' then
        return 4
    end
    if status == 'RESTOCKED' then
        return 5
    end
    if status ~= 'RESERVED' then
        return -2
    end

    if existingOrderId ~= ARGV[1]
        or existingProductId ~= ARGV[2]
        or existingReservationId ~= ARGV[3]
        or existingQuantity ~= quantity then
        return -1
    end
    return 2
end

-- Validate every key before the first mutation because Redis Lua errors do not roll back earlier writes.
local stockType = redis.call('TYPE', KEYS[1]).ok
local holdCountType = redis.call('TYPE', KEYS[3]).ok
local expirationType = redis.call('TYPE', KEYS[4]).ok
if stockType ~= 'string' or holdCountType ~= 'string' then
    return -3
end
if expirationType ~= 'none' and expirationType ~= 'zset' then
    return -3
end

local stock = tonumber(redis.call('GET', KEYS[1]))
local holdCount = tonumber(redis.call('GET', KEYS[3]))
if stock == nil or stock < 0 or holdCount == nil or holdCount < 0 then
    return -3
end
if stock < quantity then
    return 0
end

redis.call('DECRBY', KEYS[1], quantity)
redis.call(
    'HSET', KEYS[2],
    'orderId', ARGV[1],
    'productId', ARGV[2],
    'reservationId', ARGV[3],
    'quantity', ARGV[4],
    'expiresAtEpochMilli', ARGV[5],
    'status', 'RESERVED'
)
redis.call('SET', KEYS[3], holdCount + quantity)
redis.call('ZADD', KEYS[4], ARGV[5], ARGV[3])

return 1
