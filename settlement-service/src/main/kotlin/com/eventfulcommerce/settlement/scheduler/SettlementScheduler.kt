package com.eventfulcommerce.settlement.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

@Component
class SettlementScheduler(
    private val jobLauncher: JobLauncher,
    private val dailySettlementJob: Job
) {
    @Scheduled(cron = "\${settlement.batch-cron}")
    fun dailyConfirmation() {
        // 전날 생성된 정산을 확정
        val targetDate = LocalDate.now().minusDays(1).toString()

        val jobParameters = JobParametersBuilder()
            .addString("targetDate", targetDate)
            // timestamp를 추가해서 같은 날짜로 재실행 시에도 새 JobInstance로 처리
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()

        logger.info { "일별 정산 배치 시작: targetDate=$targetDate" }

        try {
            val execution = jobLauncher.run(dailySettlementJob, jobParameters)
            logger.info { "일별 정산 배치 완료: status=${execution.status}, targetDate=$targetDate" }
        } catch (e: Exception) {
            logger.error { "일별 정산 배치 실패: targetDate=$targetDate, error=${e.message}" }
        }
    }
}
