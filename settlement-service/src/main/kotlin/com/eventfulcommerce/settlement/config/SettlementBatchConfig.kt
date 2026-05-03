package com.eventfulcommerce.settlement.config

import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.repository.SettlementRepository
import jakarta.persistence.EntityManagerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JpaPagingItemReader
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.time.ZoneOffset

@Configuration
class SettlementBatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val settlementRepository: SettlementRepository
) {
    companion object {
        const val CHUNK_SIZE = 1000
    }

    @Bean
    fun dailySettlementJob(dailyConfirmStep: Step): Job =
        JobBuilder("dailySettlementConfirmJob", jobRepository)
            .start(dailyConfirmStep)
            .build()

    @Bean
    fun dailyConfirmStep(
        settlementReader: JpaPagingItemReader<Settlement>,
        settlementProcessor: ItemProcessor<Settlement, Settlement>,
        settlementWriter: ItemWriter<Settlement>
    ): Step =
        StepBuilder("dailyConfirmStep", jobRepository)
            .chunk<Settlement, Settlement>(CHUNK_SIZE, transactionManager)
            .reader(settlementReader)
            .processor(settlementProcessor)
            .writer(settlementWriter)
            .build()

    // @StepScope: jobParameters를 주입받기 위해 Step 실행 시점에 빈 생성
    @Bean
    @StepScope
    fun settlementReader(
        @Value("#{jobParameters['targetDate']}") targetDate: String
    ): JpaPagingItemReader<Settlement> {
        val targetInstant = LocalDate.parse(targetDate)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()

        return JpaPagingItemReaderBuilder<Settlement>()
            .name("settlementReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString(
                "SELECT s FROM Settlement s " +
                "WHERE s.status = :status AND s.createdAt < :targetDate " +
                "ORDER BY s.createdAt ASC"
            )
            .parameterValues(mapOf(
                "status" to SettlementStatus.PENDING,
                "targetDate" to targetInstant
            ))
            .pageSize(CHUNK_SIZE)
            .build()
    }

    @Bean
    fun settlementProcessor(): ItemProcessor<Settlement, Settlement> =
        ItemProcessor { settlement ->
            settlement.confirm()
            settlement
        }

    @Bean
    fun settlementWriter(): ItemWriter<Settlement> =
        ItemWriter { items -> settlementRepository.saveAll(items) }
}
