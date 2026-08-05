-- KEYS[1] = stockKey
-- KEYS[2] = holdKey (COMMITTED/RESTOCKED tombstone)
-- KEYS[3] = expirationKey
-- ARGV[1] = quantity
-- ARGV[2] = reservationId
-- ARGV[3] = terminalRetentionMillis
-- ARGV[4] = productId
-- ARGV[5] = terminalAtEpochMilli

local quantity = tonumber(ARGV[1])
local retentionMillis = tonumber(ARGV[3])
local terminalAt = tonumber(ARGV[5])
if quantity == nil or quantity <= 0
    or retentionMillis == nil or retentionMillis <= 0
    or terminalAt == nil or terminalAt <= 0 then
    return -1
end

local expirationType = redis.call('TYPE', KEYS[3]).ok
local stockType = redis.call('TYPE', KEYS[1]).ok
local stock = tonumber(redis.call('GET', KEYS[1]))
if expirationType ~= 'none' and expirationType ~= 'zset' then
    return -1
end
if stockType ~= 'string' or stock == nil or stock < 0 then
    return -1
end

local holdExists = redis.call('EXISTS', KEYS[2]) == 1
if holdExists then
    if redis.call('TYPE', KEYS[2]).ok ~= 'hash' then
        return -1
    end

    local status = redis.call('HGET', KEYS[2], 'status') or 'RESERVED'
    local storedReservationId = redis.call('HGET', KEYS[2], 'reservationId')
    local storedProductId = redis.call('HGET', KEYS[2], 'productId')
    local storedQuantity = tonumber(redis.call('HGET', KEYS[2], 'quantity'))
    if storedReservationId ~= ARGV[2]
        or storedProductId ~= ARGV[4]
        or storedQuantity ~= quantity then
        return -1
    end

    if status == 'RESTOCKED' then
        redis.call('ZREM', KEYS[3], ARGV[2])
        redis.call('PEXPIRE', KEYS[2], retentionMillis)
        return 2
    end
    if status ~= 'COMMITTED' then
        return 3
    end
end

-- Missing holds are supported for orders confirmed before terminal tombstones were introduced.
-- The RESTOCKED tombstone created below makes that migration path idempotent as well.
redis.call('INCRBY', KEYS[1], quantity)
redis.call('ZREM', KEYS[3], ARGV[2])
if holdExists then
    redis.call('HSET', KEYS[2], 'status', 'RESTOCKED', 'terminalAtEpochMilli', ARGV[5])
else
    redis.call(
        'HSET', KEYS[2],
        'productId', ARGV[4],
        'reservationId', ARGV[2],
        'quantity', ARGV[1],
        'status', 'RESTOCKED',
        'terminalAtEpochMilli', ARGV[5]
    )
end
redis.call('PEXPIRE', KEYS[2], retentionMillis)

return 1
