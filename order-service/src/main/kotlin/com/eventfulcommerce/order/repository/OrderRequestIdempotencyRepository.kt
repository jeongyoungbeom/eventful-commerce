package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.OrderRequestIdempotency
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OrderRequestIdempotencyRepository : JpaRepository<OrderRequestIdempotency, UUID> {
    /**
     * PostgreSQL unique conflict is used as the concurrency-safe request claim.
     * A losing request waits for the winning transaction to commit, then reads its stored response.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO order_request_idempotency
                (id, user_id, idempotency_key, request_hash, expires_at, created_at, updated_at)
            VALUES
                (:id, :userId, :idempotencyKey, :requestHash, :expiresAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("requestHash") requestHash: String,
        @Param("expiresAt") expiresAt: Instant
    ): Int

    @Query(
        value = "SELECT pg_try_advisory_xact_lock(hashtextextended(:lockKey, 0))",
        nativeQuery = true
    )
    fun tryAcquireClaimLock(@Param("lockKey") lockKey: String): Boolean

    @Modifying(flushAutomatically = true)
    @Query(
        """
        DELETE FROM OrderRequestIdempotency e
        WHERE e.userId = :userId
          AND e.idempotencyKey = :idempotencyKey
          AND e.expiresAt <= :now
        """
    )
    fun deleteExpiredByUserIdAndIdempotencyKey(
        @Param("userId") userId: UUID,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("now") now: Instant
    ): Int

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM OrderRequestIdempotency e WHERE e.expiresAt <= :now")
    fun deleteAllExpired(@Param("now") now: Instant): Int

    fun findByUserIdAndIdempotencyKey(
        userId: UUID,
        idempotencyKey: String
    ): OrderRequestIdempotency?

    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            UPDATE order_request_idempotency
            SET response_json = :responseJson,
                order_id = CAST(:orderId AS uuid),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND response_json IS NULL
        """,
        nativeQuery = true
    )
    fun complete(
        @Param("id") id: UUID,
        @Param("responseJson") responseJson: String,
        @Param("orderId") orderId: UUID?
    ): Int
}
