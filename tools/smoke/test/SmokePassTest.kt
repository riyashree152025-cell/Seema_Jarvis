package smoke

import org.junit.Assert.assertEquals
import org.junit.Test

/** Smoke fixture: tests that must pass. */
class SmokePassTest {

    @Test
    fun additionWorks() {
        assertEquals(4, Trivial.add(2, 2))
    }

    @Test
    fun greetingWorks() {
        assertEquals("hello, seema", Trivial.greet("seema"))
    }
}
