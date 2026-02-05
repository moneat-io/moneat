package com.jetbrains.kmpapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

@Composable
fun App() {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface {
            ErrorTestingScreen()
        }
    }
}

@Composable
fun ErrorTestingScreen() {
    var logText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    
    fun log(message: String) {
        logText = "${System.currentTimeMillis()}: $message\n$logText"
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "Moneat KMP E2E Testing",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Test various error scenarios",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Error Scenarios",
            style = MaterialTheme.typography.titleLarge
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                log("Triggering crash...")
                triggerCrash()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Trigger Crash")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                log("Throwing exception...")
                triggerException { log(it) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Throw Exception")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                log("Simulating network error...")
                triggerNetworkError { log(it) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Network Error")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                log("Triggering background crash...")
                triggerBackgroundCrash()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Background Crash")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                log("Triggering null pointer...")
                triggerNullPointer { log(it) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Null Pointer")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { logText = "" },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear Log")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Test Results",
            style = MaterialTheme.typography.titleLarge
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = logText,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            )
        }
    }
}

expect fun triggerCrash()
expect fun triggerException(onResult: (String) -> Unit)
expect fun triggerNetworkError(onResult: (String) -> Unit)
expect fun triggerBackgroundCrash()
expect fun triggerNullPointer(onResult: (String) -> Unit)
