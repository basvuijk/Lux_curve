package com.example.luxcurve.ha

import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the whole suite of HaTest.kt as one JUnit test. */
class HaSuiteTest {
    @Test
    fun allChecksPass() {
        runAll()
        assertEquals("$failures of $checks checks failed:\n$failureLog", 0, failures)
    }
}
