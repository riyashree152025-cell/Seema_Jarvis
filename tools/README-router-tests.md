# Offline Kotlin + JUnit runner (Gradle-free)

`run-router-tests.ps1` compiles the router slice with the Kotlin compiler taken from the
Gradle cache and runs its JUnit 4 tests. It exists because **Gradle cannot run in this
environment**:

* `.\gradlew.bat` → `Error: Unable to access jarfile ...\??????\...\gradle-wrapper.jar`
  (cmd.exe mangles the non-ASCII `ドキュメント` path), and
* `java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain`
  → `java.io.FileNotFoundException: ~\.gradle\wrapper\dists\...\gradle-8.9-bin.zip.lck
  (Access is denied)`, because the DSH sandbox is **workspace-write** and `~/.gradle` is
  outside the workspace.

The cache and the Android SDK are only ever **read**. Every write lands in
`Seema_Jarvis/build-verify/` (`classes/`, `aar/`, `tmp/`, `home/`, `last-run.log`).

## The command a reviewer runs

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "Seema_Jarvis\tools\run-router-tests.ps1"
```

Defaults: the router package + the three `apikeys/data` sources it depends on, and the
tests in `app/src/test/java/com/seema/assistant/router`. Override with `-SourcePath`,
`-TestPath`, `-TestClass` (comma-separate multiple values), `-ExtraClasspath`.

Current result on this checkout:

```
JUnit version 4.13.2
OK (19 tests)
ROUTER-TESTS: PASS tests=19 passed=19 failed=0
EXIT CODE: 0
```

## Proving the runner itself

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "Seema_Jarvis\tools\run-smoke-test.ps1"
```

Fixtures live in `tools/smoke/src` (production) and `tools/smoke/test` (two passing tests
plus one deliberately failing test). The driver asserts four things and exits non-zero if
any of them regresses:

| step | expectation |
| --- | --- |
| `pass-only` | exit 0, `ROUTER-TESTS: PASS tests=2 passed=2 failed=0` |
| `deliberate-failure` | exit 1, `Tests run: 3,  Failures: 1`, `FAILURES!!!` |
| `missing-source-error` | exit 3, `MISSING PRODUCTION SOURCES` with the searched path |
| `list-classpath` | exit 0, every resolved jar printed |

## Exit codes

| code | meaning |
| --- | --- |
| 0 | compile OK and all tests passed (or `-SkipTests` with a clean compile) |
| 1 | compile OK, at least one test failed |
| 2 | infrastructure (missing jar / AAR extraction / java did not start) |
| 3 | bad input (missing source or test path, unknown test class) |
| 4 | Kotlin compilation failed, or JUnit output unparseable |

## How it works (and two traps it avoids)

1. **Compiler from the cache.** `java -cp kotlin-compiler-embeddable-2.0.21.jar;... org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`.
   The compiler's own JVM also needs `kotlin-stdlib`, `kotlinx-coroutines-core-jvm`,
   `org.jetbrains:annotations` and `trove4j`, otherwise it dies with
   `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` /
   `kotlinx/coroutines/CoroutineScope` / `gnu/trove/TObjectHashingStrategy` /
   `org/jetbrains/annotations/NotNull`.
2. **AndroidX ships `.aar`, not `.jar`.** `androidx.security:security-crypto` (needed by
   `ApiKeyVault.kt` via `EncryptedSharedPreferences`) is extracted from
   `~/.gradle/.../security-crypto-1.1.0-alpha06.aar` → `build-verify/aar/*-classes.jar`.
   Reuse is decided by the uncompressed `classes.jar` size, **not** by timestamp: AARs
   carry a fixed 1979 zip-entry timestamp, so any mtime check re-extracts every run.
3. **The non-ASCII path trap.** The Windows JVM launcher decodes its command line with the
   ANSI code page, so an absolute path containing `ドキュメント` reaches Java as `??????`
   and cannot be opened. All workspace paths are therefore passed **relative** to a working
   directory set through .NET (Unicode-correct); cache/SDK paths are pure ASCII and safe.
   `-Djava.io.tmpdir` / `-Duser.home` point at `build-verify/tmp` and `build-verify/home`
   so no JVM temp file escapes the workspace.
4. **Stale classes must never be discovered.** Output directories are deleted and
   recreated before each compile (`-LiteralPath` does not expand `*`, so deleting
   `dir\*` silently leaves old classes behind).

`android.jar` is on the **compile** classpath only. It is excluded from the test runtime
classpath on purpose: its classes are stubs (and it ships an old `junit.framework`).

## Resolved classpath (verified absolute paths)

Compile classpath:

```
Seema_Jarvis\build-verify\aar\security-crypto-1.1.0-alpha06-classes.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.0.21\618b539767b4899b4660a83006e052b63f1db551\kotlin-stdlib-2.0.21.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0\9beade4c1c1569e4f36cbd2c37e02e3e41502601\kotlinx-coroutines-core-jvm-1.9.0.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\com.squareup.retrofit2\retrofit\2.11.0\6ca8c6caf842271f3232e075519fe04081ef7069\retrofit-2.11.0.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\com.squareup.okhttp3\okhttp\4.12.0\2f4525d4a200e97e1b87449c2cd9bd2e25b7e8cd\okhttp-4.12.0.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\com.squareup.okio\okio-jvm\3.6.0\5600569133b7bdefe1daf9ec7f4abeb6d13e1786\okio-jvm-3.6.0.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\junit\junit\4.13.2\8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12\junit-4.13.2.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\org.hamcrest\hamcrest-core\1.3\42a25dc3219429f0e5d060061f71acb49bf010a0\hamcrest-core-1.3.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\org.jetbrains\annotations\13.0\919f0dfe192fb4e063e7dacadee7f8bb9a2672a9\annotations-13.0.jar
C:\Users\rajub\.gradle\caches\modules-2\files-2.1\androidx.annotation\annotation\1.3.0\21f49f5f9b85fc49de712539f79123119740595\annotation-1.3.0.jar
C:\Users\rajub\AppData\Local\Android\Sdk\platforms\android-35\android.jar        (compile only)
```

Compiler JVM classpath: `kotlin-compiler-embeddable-2.0.21`, `trove4j-1.0.20200330`, plus
the stdlib / coroutines / annotations / retrofit / okhttp / okio / junit / hamcrest /
androidx.annotation jars above.

Versions mirror `gradle/libs.versions.toml` (kotlin 2.0.21, coroutines 1.9.0, retrofit
2.11.0, okhttp 4.12.0, okio 3.6.0 via okhttp, junit 4.13.2, security-crypto 1.1.0-alpha06).
If a jar is genuinely absent the script fails with exit 2 and prints the cache directory it
searched — it never invents a fallback.