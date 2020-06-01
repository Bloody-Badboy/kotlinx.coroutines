/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlinx.atomicfu.*

public actual val isStressTest: Boolean = false
public actual val stressTestMultiplier: Int = 1

public actual open class TestBase actual constructor() {
    private val actionIndex = atomic(0)
    private val finished = atomic(false)
    private val error = atomic<Throwable?>(null)

    /**
     * Throws [IllegalStateException] like `error` in stdlib, but also ensures that the test will not
     * complete successfully even if this exception is consumed somewhere in the test.
     */
    @Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
    public actual fun error(message: Any, cause: Throwable? = null): Nothing {
        val exception = IllegalStateException(message.toString(), cause)
        error.compareAndSet(null, exception)
        throw exception
    }

    private fun printError(message: String, cause: Throwable) {
        error.compareAndSet(null, cause)
        println(message)
        cause.printStackTrace()
    }

    /**
     * Asserts that this invocation is `index`-th in the execution sequence (counting from one).
     */
    public actual fun expect(index: Int) {
        val wasIndex = actionIndex.incrementAndGet()
        check(index == wasIndex) { "Expecting action index $index but it is actually $wasIndex" }
    }

    /**
     * Asserts that this line is never executed.
     */
    public actual fun expectUnreached() {
        error("Should not be reached")
    }

    /**
     * Asserts that this it the last action in the test. It must be invoked by any test that used [expect].
     */
    public actual fun finish(index: Int) {
        expect(index)
        val old = finished.getAndSet(true)
        check(!old) { "Should call 'finish(...)' at most once" }
    }

    /**
     * Asserts that [finish] was invoked
     */
    public actual fun ensureFinished() {
        require(finished.value) { "finish(...) should be caller prior to this check" }
    }

    public actual fun reset() {
        check(actionIndex.value == 0 || finished.value) { "Expecting that 'finish(...)' was invoked, but it was not" }
        actionIndex.value = 0
        finished.value = false
    }

    @Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
    public actual fun runTest(
        expected: ((Throwable) -> Boolean)? = null,
        unhandled: List<(Throwable) -> Boolean> = emptyList(),
        block: suspend CoroutineScope.() -> Unit
    ) {
        val exCount = atomic(0)
        val ex = atomic<Throwable?>(null)
        try {
            runBlocking(block = block, context = CoroutineExceptionHandler { _, e ->
                if (e is CancellationException) return@CoroutineExceptionHandler // are ignored
                val result = exCount.incrementAndGet()
                when {
                    result > unhandled.size ->
                        printError("Too many unhandled exceptions $result, expected ${unhandled.size}, got: $e", e)
                    !unhandled[result - 1](e) ->
                        printError("Unhandled exception was unexpected: $e", e)
                }
            })
        } catch (e: Throwable) {
            ex.value = e
            if (expected != null) {
                if (!expected(e))
                    error("Unexpected exception: $e", e)
            } else
                throw e
        } finally {
            if (ex.value == null && expected != null) error("Exception was expected but none produced")
        }
        if (exCount.value < unhandled.size)
            error("Too few unhandled exceptions $exCount, expected ${unhandled.size}")
    }
}
