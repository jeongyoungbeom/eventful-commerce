-- KEYS[1] = holdKey
-- KEYS[2] = holdCountKey
-- KEYS[3] = expirationKey
-- ARGV[1] = fallback quantity for legacy string holds
-- ARGV[2] = reservationId
-- ARGV[3] = terminalRetentionMillis
-- ARGV[4] = productId
-- ARGV[5] = terminalAtEpochMilli

local expirationType = redis.call('TYPE', KEYS[3]).ok
if expirationType ~= 'none' and expirationType ~= 'zset' then
    return -1
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('ZREM', KEYS[3], ARGV[2])
    return 0
end

local retentionMillis = tonumber(ARGV[3])
local terminalAt = tonumber(ARGV[5])
if retentionMillis == nil or retentionMillis <= 0 or terminalAt == nil or terminalAt <= 0 then
    return -1
end

local holdType = redis.call('TYPE', KEYS[1]).ok
local quantity
local status = 'RESERVED'
if holdType == 'hash' then
    status = redis.call('HGET', KEYS[1], 'status') or 'RESERVED'
    quantity = tonumber(redis.call('HGET', KEYS[1], 'quantity'))

    local storedReservationId = redis.call('HGET', KEYS[1], 'reservationId')
    local storedProductId = redis.call('HGET', KEYS[1], 'productId')
    if storedReservationId ~= nil and storedReservationId ~= ARGV[2] then
        return -1
    end
    if storedProductId ~= nil and storedProductId ~= ARGV[4] then
        return -1
    end
elseif holdType == 'string' then
    quantity = tonumber(ARGV[1])
else
    return -1
end

if quantity == nil or quantity <= 0 or tonumber(ARGV[1]) ~= quantity then
    return -1
end

if status == 'COMMITTED' then
    redis.call('ZREM', KEYS[3], ARGV[2])
    redis.call('PEXPIRE', KEYS[1], retentionMillis)
    return 2
end
if status == 'RELEASED' or status == 'RESTOCKED' then
    return 3
end
if status ~= 'RESERVED' then
    return -1
end

local holdCountType = redis.call('TYPE', KEYS[2]).ok
local holdCount = tonumber(redis.call('GET', KEYS[2]))
if holdCountType ~= 'string' or holdCount == nil or holdCount < quantity then
    return -1
end

-- All type/value checks are complete before the first state-changing command.
redis.call('SET', KEYS[2], holdCount - quantity)
redis.call('ZREM', KEYS[3], ARGV[2])
if holdType == 'hash' then
    redis.call('HSET', KEYS[1], 'status', 'COMMITTED', 'terminalAtEpochMilli', ARGV[5])
else
    redis.call('DEL', KEYS[1])
    redis.call(
        'HSET', KEYS[1],
        'productId', ARGV[4],
        'reservationId', ARGV[2],
        'quantity', ARGV[1],
        'status', 'COMMITTED',
        'terminalAtEpochMilli', ARGV[5]
    )
end
redis.call('PEXPIRE', KEYS[1], retentionMillis)

return 1
