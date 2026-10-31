package smoke

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke fixture: exactly ONE deliberately failing test. Its only purpose is to
 * prove the runner surfaces the failure and exits non-zero. It is never part of
 * the router test set; the smoke driver invokes it explicitly.
 */
class SmokeFailTest {

    @Test
    fun deliberateFailure() {
        assertEquals("deliberate smoke failure", 3L, Trivial.add(1, 1).toLong())
    }
}
