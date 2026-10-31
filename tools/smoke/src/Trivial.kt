package smoke

/**
 * Trivial "production" class used only by the offline runner smoke test.
 * It exercises the real compile path (K2JVMCompiler) without needing any
 * Android / Retrofit dependency to be present, so the smoke test proves the
 * harness itself rather than the project's dependency graph.
 */
object Trivial {
    fun add(a: Int, b: Int): Int = a + b
    fun greet(name: String): String = "hello, $name"
}
