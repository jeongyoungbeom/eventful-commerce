package com.eventfulcommerce.product.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class ImageStorageService(
    @Value("\${image.upload-dir}") private val uploadDir: String,
    @Value("\${image.base-url}") private val baseUrl: String
) {
    fun save(file: MultipartFile): String {
        val dir = File(uploadDir).also { if (!it.exists()) check(it.mkdirs() || it.isDirectory) { "이미지 디렉터리 생성 실패" } }
        val ext = file.originalFilename?.substringAfterLast('.', "jpg") ?: "jpg"
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        try {
            file.transferTo(target)
        } catch (failure: Exception) {
            deleteFile(target)
            throw failure
        }
        return "$baseUrl/${target.name}"
    }

    fun replace(files: List<MultipartFile>, previousUrls: List<String>): List<String> {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "이미지 교체에는 DB 트랜잭션이 필요합니다" }
        // shortcut: 프로세스 중단·커밋 결과 불명 시 파일이 남을 수 있다. 운영 자동 정리가 필요하면 영속 정리 작업으로 전환한다.
        val uploaded = mutableListOf<String>()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() { previousUrls.forEach(::deleteByUrl) }
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) uploaded.forEach(::deleteByUrl)
            }
        })
        files.forEach { uploaded.add(save(it)) }
        return uploaded
    }

    fun deleteByUrl(url: String) = deleteFile(File(uploadDir, url.substringAfterLast('/')))

    private fun deleteFile(file: File) {
        try {
            check(!file.exists() || file.delete()) { "파일 삭제 실패" }
        } catch (failure: Exception) {
            logger.warn(failure) { "이미지 정리 실패: ${file.name}" }
        }
    }
}
