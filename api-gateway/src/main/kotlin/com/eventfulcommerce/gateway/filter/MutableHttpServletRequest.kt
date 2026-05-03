package com.eventfulcommerce.gateway.filter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.util.Collections
import java.util.Enumeration

class MutableHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {

    private val customHeaders = mutableMapOf<String, String>()

    fun addHeader(name: String, value: String) {
        customHeaders[name] = value
    }

    override fun getHeader(name: String): String? =
        customHeaders[name] ?: super.getHeader(name)

    override fun getHeaders(name: String): Enumeration<String> {
        if (customHeaders.containsKey(name)) {
            return Collections.enumeration(listOf(customHeaders[name]!!))
        }
        return super.getHeaders(name)
    }

    override fun getHeaderNames(): Enumeration<String> {
        val names = customHeaders.keys.toMutableList()
        val superNames = super.getHeaderNames()
        while (superNames.hasMoreElements()) names.add(superNames.nextElement())
        return Collections.enumeration(names)
    }
}
