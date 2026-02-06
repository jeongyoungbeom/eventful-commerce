package com.eventfulcommerce.common.repository

import com.eventfulcommerce.common.ProcessedEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, UUID>