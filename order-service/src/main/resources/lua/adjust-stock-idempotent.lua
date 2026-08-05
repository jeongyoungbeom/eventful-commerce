-- KEYS[1]: available stock
-- KEYS[2]: event tombstone (same product hash slot)
-- ARGV[1]: stock delta
-- ARGV[2]: tombstone retention milliseconds

local stockType = redis.call('TYPE', KEYS[1]).ok
local markerType = redis.call('TYPE', KEYS[2]).ok
local delta = tonumber(ARGV[1])
local retentionMillis = tonumber(ARGV[2])

if markerType == 'string' then
    return 2
end

if markerType ~= 'none' or stockType ~= 'string' or delta == nil or retentionMillis == nil then
    return -1
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock % 1 ~= 0 or delta % 1 ~= 0 or retentionMillis <= 0 then
    return -1
end

local newStock = stock + delta
if newStock < 0 then
    return -1
end

redis.call('SET', KEYS[1], tostring(newStock))
redis.call('SET', KEYS[2], 'APPLIED', 'PX', retentionMillis)
return 1
