#Requires -Version 7.0
<#
.SYNOPSIS
    Self-verifying smoke test for tools/run-router-tests.ps1.

.DESCRIPTION
    Proves the offline runner works end-to-end without touching the real router:

      1. compiles tools/smoke/src/Trivial.kt and runs smoke.SmokePassTest
         -> expects exit 0 and "ROUTER-TESTS: PASS"
      2. runs smoke.SmokePassTest + smoke.SmokeFailTest together
         -> expects exit 1 and "ROUTER-TESTS: FAIL ... failed=1"
      3. points -SourcePath at the not-yet-written router package
         -> expects exit 3 and a clear "MISSING PRODUCTION SOURCES" report
      4. resolves the full classpath with -ListClasspath
         -> expects exit 0 and the resolved jar list

    Exit code 0 = every expectation held (SMOKE PASS), 1 = something regressed.

.EXAMPLE
    pwsh -NoProfile -File Seema_Jarvis/tools/run-smoke-test.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$script:ProjectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$script:Runner      = Join-Path $PSScriptRoot 'run-router-tests.ps1'
$script:PwshExe     = (Get-Process -Id $PID).Path

if (-not (Test-Path -LiteralPath $script:Runner -PathType Leaf)) {
    Write-Host "FATAL: runner not found: $($script:Runner)" -ForegroundColor Red
    exit 1
}

function Invoke-RunnerStep {
    param([string]$Name, [string[]]$RunnerArgs)

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $script:PwshExe
    $psi.WorkingDirectory       = $script:ProjectRoot
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    try {
        $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $psi.StandardErrorEncoding  = [System.Text.UTF8Encoding]::new($false)
    } catch { }

    foreach ($a in @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script:Runner) + $RunnerArgs) {
        [void]$psi.ArgumentList.Add([string]$a)
    }

    $proc = [System.Diagnostics.Process]::Start($psi)
    $o = $proc.StandardOutput.ReadToEndAsync()
    $e = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()
    return [pscustomobject]@{
        Name     = $Name
        ExitCode = $proc.ExitCode
        Output   = ($o.Result + "`n" + $e.Result)
        Command  = ($script:PwshExe + ' ' + (($psi.ArgumentList) -join ' '))
    }
}

$results = New-Object System.Collections.Generic.List[object]

function Test-Step {
    param(
        [string]   $Name,
        [string[]] $RunnerArgs,
        [int]      $ExpectedExit,
        [string[]] $MustMatch
    )

    Write-Host ''
    Write-Host ('#' * 74) -ForegroundColor DarkCyan
    Write-Host "# STEP: $Name" -ForegroundColor Cyan
    Write-Host "# CMD : pwsh -NoProfile -File tools/run-router-tests.ps1 $($RunnerArgs -join ' ')" -ForegroundColor DarkGray
    Write-Host ('#' * 74) -ForegroundColor DarkCyan

    $step = Invoke-RunnerStep -Name $Name -RunnerArgs $RunnerArgs
    Write-Host $step.Output

    $problems = New-Object System.Collections.Generic.List[string]
    if ($step.ExitCode -ne $ExpectedExit) {
        $problems.Add("exit code $($step.ExitCode), expected $ExpectedExit")
    }
    foreach ($pattern in $MustMatch) {
        if ($step.Output -notmatch $pattern) { $problems.Add("output does not match /$pattern/") }
    }

    $ok = ($problems.Count -eq 0)
    $results.Add([pscustomobject]@{
        Step     = $Name
        Expected = $ExpectedExit
        Actual   = $step.ExitCode
        Ok       = $ok
        Problems = ($problems -join '; ')
    })

    Write-Host ("  -> exit {0} (expected {1}) : {2}" -f $step.ExitCode, $ExpectedExit, $(if ($ok) { 'EXPECTATION MET' } else { 'EXPECTATION FAILED' })) `
        -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
    if (-not $ok) { foreach ($p in $problems) { Write-Host "     $p" -ForegroundColor Red } }
    return $ok
}

Write-Host ('=' * 74) -ForegroundColor Cyan
Write-Host 'SMOKE TEST :: tools/run-router-tests.ps1' -ForegroundColor Cyan
Write-Host ('=' * 74) -ForegroundColor Cyan
Write-Host "runner : $($script:Runner)"

$smokeSource = 'tools/smoke/src'
$smokeTest   = 'tools/smoke/test'

# 1. passing-only run -> exit 0
[void](Test-Step -Name 'pass-only' `
    -RunnerArgs @('-SourcePath', $smokeSource, '-TestPath', $smokeTest, '-TestClass', 'smoke.SmokePassTest') `
    -ExpectedExit 0 `
    -MustMatch @('ROUTER-TESTS: PASS tests=2 passed=2 failed=0', 'OK \(2 tests\)'))

# 2. mixed run, one deliberately failing test -> exit 1
[void](Test-Step -Name 'deliberate-failure' `
    -RunnerArgs @('-SourcePath', $smokeSource, '-TestPath', $smokeTest, '-TestClass', 'smoke.SmokePassTest,smoke.SmokeFailTest') `
    -ExpectedExit 1 `
    -MustMatch @('ROUTER-TESTS: FAIL tests=3 passed=2 failed=1', 'deliberateFailure\(smoke\.SmokeFailTest\)', 'FAILURES!!!'))

# 3. missing production source -> exit 3 with an explicit searched-path report
[void](Test-Step -Name 'missing-source-error' `
    -RunnerArgs @('-SourcePath', 'app/src/main/java/com/seema/assistant/router/THIS_DOES_NOT_EXIST', '-TestPath', $smokeTest) `
    -ExpectedExit 3 `
    -MustMatch @('MISSING PRODUCTION SOURCES', 'THIS_DOES_NOT_EXIST', 'ROUTER-TESTS: ERROR \(exit 3\)'))

# 4. classpath resolution only -> exit 0, every jar listed
[void](Test-Step -Name 'list-classpath' `
    -RunnerArgs @('-ListClasspath') `
    -ExpectedExit 0 `
    -MustMatch @('ROUTER-TESTS: CLASSPATH-ONLY', 'kotlin-compiler-embeddable', 'security-crypto', 'android-35\\android\.jar'))

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host ('=' * 74) -ForegroundColor Cyan
Write-Host 'SMOKE SUMMARY' -ForegroundColor Cyan
Write-Host ('=' * 74) -ForegroundColor Cyan
$failed = 0
foreach ($r in $results) {
    $verdict = if ($r.Ok) { 'OK  ' } else { 'FAIL' }
    Write-Host ("  [{0}] {1,-24} exit={2} expected={3} {4}" -f $verdict, $r.Step, $r.Actual, $r.Expected, $r.Problems)
    if (-not $r.Ok) { $failed++ }
}
Write-Host ''
if ($failed -eq 0) {
    Write-Host "SMOKE: PASS ($($results.Count)/$($results.Count) expectations met)" -ForegroundColor Green
    exit 0
}
Write-Host "SMOKE: FAILED ($failed/$($results.Count) expectations failed)" -ForegroundColor Red
exit 1
