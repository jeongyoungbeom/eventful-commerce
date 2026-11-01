-- KEYS[1] = stockKey
-- KEYS[2] = holdKey
-- KEYS[3] = holdCountKey
-- KEYS[4] = expirationKey
-- ARGV[1] = fallback quantity for legacy string holds
-- ARGV[2] = reservationId
-- ARGV[3] = terminalRetentionMillis
-- ARGV[4] = productId
-- ARGV[5] = terminalAtEpochMilli

local expirationType = redis.call('TYPE', KEYS[4]).ok
if expirationType ~= 'none' and expirationType ~= 'zset' then
    return -1
end

if redis.call('EXISTS', KEYS[2]) == 0 then
    -- S4는 생성 전 해제도 원본과 함께 종결시켜 늦은 예약을 막는다.
    if ARGV[6] ~= nil then
        local quantity = tonumber(ARGV[1])
        local retention = tonumber(ARGV[3])
        local expiry = tonumber(ARGV[7])
        if quantity == nil or quantity <= 0 or retention == nil or retention <= 0 or expiry == nil then return -1 end
        redis.call('HSET', KEYS[2], 'orderId', ARGV[6], 'productId', ARGV[4],
            'reservationId', ARGV[2], 'quantity', ARGV[1], 'expiresAtEpochMilli', ARGV[7],
            'status', 'RELEASED', 'terminalAtEpochMilli', ARGV[5])
        redis.call('PEXPIRE', KEYS[2], retention)
        redis.call('ZREM', KEYS[4], ARGV[2])
        return 1
    end
    redis.call('ZREM', KEYS[4], ARGV[2])
    return 0
end

local retentionMillis = tonumber(ARGV[3])
local terminalAt = tonumber(ARGV[5])
if retentionMillis == nil or retentionMillis <= 0 or terminalAt == nil or terminalAt <= 0 then
    return -1
end

local holdType = redis.call('TYPE', KEYS[2]).ok
local quantity
local status = 'RESERVED'
if holdType == 'hash' then
    status = redis.call('HGET', KEYS[2], 'status') or 'RESERVED'
    quantity = tonumber(redis.call('HGET', KEYS[2], 'quantity'))

    if ARGV[6] ~= nil and (redis.call('HGET', KEYS[2], 'orderId') ~= ARGV[6]
        or redis.call('HGET', KEYS[2], 'expiresAtEpochMilli') ~= ARGV[7]) then return -1 end
    local storedReservationId = redis.call('HGET', KEYS[2], 'reservationId')
    local storedProductId = redis.call('HGET', KEYS[2], 'productId')
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

if status == 'RELEASED' then
    redis.call('ZREM', KEYS[4], ARGV[2])
    redis.call('PEXPIRE', KEYS[2], retentionMillis)
    return 2
end
if status == 'COMMITTED' or status == 'RESTOCKED' then
    return 3
end
if status ~= 'RESERVED' then
    return -1
end

local stockType = redis.call('TYPE', KEYS[1]).ok
local holdCountType = redis.call('TYPE', KEYS[3]).ok
local stock = tonumber(redis.call('GET', KEYS[1]))
local holdCount = tonumber(redis.call('GET', KEYS[3]))
if stockType ~= 'string' or stock == nil or stock < 0 then
    return -1
end
if holdCountType ~= 'string' or holdCount == nil or holdCount < quantity then
    return -1
end

-- All type/value checks are complete before the first state-changing command.
redis.call('INCRBY', KEYS[1], quantity)
redis.call('SET', KEYS[3], holdCount - quantity)
redis.call('ZREM', KEYS[4], ARGV[2])
if holdType == 'hash' then
    redis.call('HSET', KEYS[2], 'status', 'RELEASED', 'terminalAtEpochMilli', ARGV[5])
else
    redis.call('DEL', KEYS[2])
    redis.call(
        'HSET', KEYS[2],
        'productId', ARGV[4],
        'reservationId', ARGV[2],
        'quantity', ARGV[1],
        'status', 'RELEASED',
        'terminalAtEpochMilli', ARGV[5]
    )
end
redis.call('PEXPIRE', KEYS[2], retentionMillis)

return 1
