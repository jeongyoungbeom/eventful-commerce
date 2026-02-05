package com.eventfulcommerce.payment.repository

import com.eventfulcommerce.payment.domain.entity.ProcessedEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, UUID>