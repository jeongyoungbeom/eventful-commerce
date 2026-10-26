package com.eventfulcommerce.settlement.config

import com.eventfulcommerce.settlement.domain.SettlementStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcPagingItemReader
import org.springframework.batch.item.database.Order
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

@Configuration
class SettlementBatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource
) {
    companion object {
        // 커밋 경계와 페이지 경계를 맞춰 마지막 정렬 키부터 재시작한다.
        const val CHUNK_SIZE = 1000
    }

    @Bean
    fun dailySettlementJob(dailyConfirmStep: Step): Job =
        JobBuilder("dailySettlementConfirmJob", jobRepository)
            .start(dailyConfirmStep)
            .build()

    @Bean
    fun dailyConfirmStep(
        settlementReader: JdbcPagingItemReader<UUID>,
        settlementWriter: ItemWriter<UUID>
    ): Step =
        StepBuilder("dailyConfirmStep", jobRepository)
            .chunk<UUID, UUID>(CHUNK_SIZE, transactionManager)
            .reader(settlementReader)
            .writer(settlementWriter)
            .build()

    @Bean
    @StepScope
    fun settlementReader(
        @Value("#{jobParameters['targetDate']}") targetDate: String
    ): JdbcPagingItemReader<UUID> {
        val targetInstant = LocalDate.parse(targetDate)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()

        // PENDING 집합이 줄어도 OFFSET 없이 마지막 (created_at, id) 다음을 읽는다.
        return JdbcPagingItemReaderBuilder<UUID>()
            .name("settlementReader")
            .dataSource(dataSource)
            .selectClause("SELECT id, created_at")
            .fromClause("FROM settlements")
            .whereClause("WHERE status = :status AND created_at < :targetDate")
            .parameterValues(mapOf(
                "status" to SettlementStatus.PENDING.name,
                "targetDate" to Timestamp.from(targetInstant)
            ))
            .sortKeys(linkedMapOf("created_at" to Order.ASCENDING, "id" to Order.ASCENDING))
            .rowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }
            .pageSize(CHUNK_SIZE)
            .saveState(true)
            .build()
    }

    @Bean
    fun settlementWriter(): ItemWriter<UUID> =
        JdbcBatchItemWriterBuilder<UUID>()
            .dataSource(dataSource)
            // 행 잠금 후 현재 상태를 재검사한다. 금액은 쓰지 않고 오래된 JPA writer도 version으로 막는다.
            .sql("""UPDATE settlements SET status = 'CONFIRMED', confirmed_at = ?, version = version + 1
                WHERE id = ? AND status = 'PENDING'""")
            .itemPreparedStatementSetter { id, statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now()))
                statement.setObject(2, id)
            }
            // 조회 이후 환불 또는 다른 배치가 먼저 처리한 행은 정상적인 무효 갱신이다.
            .assertUpdates(false)
            .build()
}
