package com.moneat

import com.moneat.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureSecurity()
    configureHTTP()
    configureSerialization()
    configureMonitoring()
    configureRouting()
    configureDatabases()
}
