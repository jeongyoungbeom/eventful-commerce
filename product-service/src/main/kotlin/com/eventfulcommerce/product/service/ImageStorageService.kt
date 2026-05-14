package com.eventfulcommerce.product.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.util.UUID

@Service
class ImageStorageService(
    @Value("\${image.upload-dir}") private val uploadDir: String,
    @Value("\${image.base-url}") private val baseUrl: String
) {
    fun save(file: MultipartFile): String {
        val dir = File(uploadDir).also { if (!it.exists()) it.mkdirs() }
        val ext = file.originalFilename?.substringAfterLast('.', "jpg") ?: "jpg"
        val filename = "${UUID.randomUUID()}.$ext"
        file.transferTo(File(dir, filename))
        return "$baseUrl/$filename"
    }

    fun deleteByUrl(url: String) {
        val filename = url.substringAfterLast('/')
        File("$uploadDir/$filename").delete()
    }
}
