package com.jasongzy.frpmobile.core.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRecoveryTest {
    @Test fun lastNetworkChangeIsRetainedAndStoppingCancelsPendingRecovery() = runTest {
        val enabled = MutableStateFlow(true)
        val changes = MutableSharedFlow<Unit>()
        var listeners = 0
        var recoveries = 0
        val source = flow {
            listeners++
            try { emitAll(changes) } finally { listeners-- }
        }
        backgroundScope.launch { networkRecoveryEvents(enabled, source).collect { recoveries++ } }
        runCurrent()
        assertEquals(1, listeners)
        changes.emit(Unit)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, recoveries)
        changes.emit(Unit)
        advanceTimeBy(1000)
        changes.emit(Unit)
        advanceTimeBy(4999)
        runCurrent()
        assertEquals(1, recoveries)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, recoveries)
        changes.emit(Unit)
        enabled.value = false
        runCurrent()
        assertEquals(0, listeners)
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(2, recoveries)
        enabled.value = true
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(2, recoveries)
        assertEquals(1, listeners)
    }
}
