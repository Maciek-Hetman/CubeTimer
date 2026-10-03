package com.maciekhetman.cubetimer.testutil

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Room and DataStore run on real threads, so poll while letting the test dispatcher run:
 * `advanceUntilIdle()` alone does not wait for a result that is still on its way from one of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.awaitCondition(description: String, condition: () -> Boolean) {
    repeat(300) {
        runCurrent()
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("timed out waiting for: $description")
}
