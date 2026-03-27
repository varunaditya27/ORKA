package com.orka.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

suspend fun <T> Flow<T>.awaitValue(
    timeoutMillis: Long = 5_000L,
): T = withTimeout(timeoutMillis) {
    first()
}

suspend fun <T> Flow<T>.awaitValue(
    timeoutMillis: Long = 5_000L,
    predicate: (T) -> Boolean,
): T = withTimeout(timeoutMillis) {
    first(predicate)
}
