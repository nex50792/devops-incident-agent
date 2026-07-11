package com.example.incident

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DevopsIncidentAgentApplication

fun main(args: Array<String>) {
    runApplication<DevopsIncidentAgentApplication>(*args)
}
