package com.example.incident.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Frontend (Vercel の Next.js) から REST API を叩けるよう CORS を設定する。
 * default で localhost:3000 と *.vercel.app を許可、必要なら `incident.cors.allowed-origins`
 * (comma 区切り) で追加する。
 */
@Configuration
class CorsConfig(
    @Value("\${incident.cors.allowed-origins:http://localhost:3000,http://localhost:3100}") private val allowedOrigins: String,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry
            .addMapping("/incidents/**")
            .allowedOrigins(*allowedOrigins.split(",").map { it.trim() }.toTypedArray())
            .allowedOriginPatterns("https://*.vercel.app")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
    }
}
