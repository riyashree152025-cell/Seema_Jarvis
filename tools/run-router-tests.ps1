#Requires -Version 7.0
<#
.SYNOPSIS
    Offline, workspace-local Kotlin compile + JUnit 4 runner for Seema_Jarvis.

.DESCRIPTION
    Gradle cannot run in this environment:
      * gradlew.bat fails with "Unable to access jarfile ...\??????\...gradle-wrapper.jar"
        because cmd.exe mangles the non-ASCII 'ドキュメント' path segment, and
      * invoking gradle-wrapper.jar through java fails with
        "java.io.FileNotFoundException: ~/.gradle/wrapper/dists/...lck (Access is denied)"
        because the DSH sandbox is workspace-write and ~/.gradle is outside the workspace.

    This script replaces `gradlew test` for the router slice. It drives the Kotlin
    compiler straight out of the read-only Gradle cache:

        java -cp <kotlin-compiler-embeddable-2.0.21.jar>;<compiler deps>
             org.jetbrains.kotlin.cli.jvm.K2JVMCompiler <args>

    and then runs the compiled test classes with JUnit 4's org.junit.runner.JUnitCore.
    Nothing outside the workspace is written: all outputs go to
    Seema_Jarvis/build-verify, and -Djava.io.tmpdir / -Duser.home point at
    workspace directories so the JVMs do not touch the real ~/.gradle or %TEMP%.

    Exit codes
        0  compile OK and every test passed (or -SkipTests with a clean compile)
        1  compile OK but at least one test failed / errored
        2  infrastructure failure (missing jar, AAR extraction, java did not start)
        3  bad input (missing source file/dir, missing test path, unknown test class)
        4  Kotlin compilation failed, or test output could not be interpreted

.NOTES
    Why every workspace path is handed to the JVM RELATIVE to the project root:
    the Windows JVM launcher decodes its command line using the ANSI code page
    (sun.jnu.encoding), so an absolute path containing 'ドキュメント' reaches Java as
    '??????' and cannot be opened. Absolute cache/Android-SDK paths are pure ASCII
    and therefore safe. Workspace paths are made relative to a working directory
    set through .NET (Unicode-correct) instead of being passed on the command line.

    Test-file encodings are read as UTF-8 because -Dfile.encoding=UTF-8 is set.
    (Diagnostic text that quotes an absolute workspace path may still show '?' for
    the 'ドキュメント' segment: that is java's stdout code page, not a real path error.)

.PARAMETER SourcePath
    Production sources to compile: directories (searched recursively for *.kt) or
    individual .kt files. Absolute, or relative to the project root.
    Default: the router package plus the three apikeys/data files it depends on.

.PARAMETER TestPath
    Test sources: a directory (recursive *.kt) or individual .kt files.
    Default: app/src/test/java/com/seema/assistant/router, falling back with a
    printed NOTICE to app/src/test/java when the router test dir does not exist yet.

.PARAMETER TestClass
    Fully-qualified names of the test classes to run. Default: every compiled
    top-level class whose simple name looks like a test (Test*, *Test, *Tests, *TestCase).
    Several classes may be given comma-separated: -TestClass 'a.BTest,c.DTest'.

.PARAMETER ExtraClasspath
    Extra jars (absolute, or relative to the project root) appended to both the
    compile and the test runtime classpath. Comma-separate several jars.

.PARAMETER BuildDir
    Output root. Default: <project>/build-verify. Must stay inside the workspace.

.PARAMETER GradleCache
    Gradle modules-2 cache root. Default: $env:USERPROFILE\.gradle\caches\modules-2\files-2.1

.PARAMETER AndroidJar
    Default: %LOCALAPPDATA%\Android\Sdk\platforms\android-35\android.jar

.PARAMETER JavaExe
    java executable. Default: java (from PATH). JDK 17+ recommended (JDK 21 verified).

.PARAMETER SkipTests
    Compile production + test sources only; do not execute JUnit.

.PARAMETER ListClasspath
    Resolve and print the full classpath, then exit without compiling.

.EXAMPLE
    pwsh -NoProfile -File Seema_Jarvis/tools/run-router-tests.ps1

.EXAMPLE
    # explicit paths, only one test class
    pwsh -NoProfile -File Seema_Jarvis/tools/run-router-tests.ps1 `
        -SourcePath app/src/main/java/com/seema/assistant/router `
        -TestPath   app/src/test/java/com/seema/assistant/router `
        -TestClass  com.seema.assistant.router.RouterTest
#>
[CmdletBinding()]
param(
    [string[]] $SourcePath,
    [string[]] $TestPath,
    [string[]] $TestClass,
    [string[]] $ExtraClasspath,
    [string]   $BuildDir,
    [string]   $GradleCache,
    [string]   $AndroidJar,
    [string]   $JavaExe = 'java',
    [switch]   $SkipTests,
    [switch]   $ListClasspath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# ---------------------------------------------------------------------------
# `pwsh -File` binds only ONE value per named array parameter: in
#   -TestClass a b
# the bare 'b' is treated as the next positional argument (here: -ExtraClasspath).
# Accepting comma-separated values makes every invocation style unambiguous:
#   -TestClass 'a,b'   (single token: for -File / ArgumentList callers)
#   -TestClass a,b     (PowerShell array: interactive)
# FQCNs and cache paths never contain a comma.
# ---------------------------------------------------------------------------
function Split-CommaList {
    param([string[]]$Values)
    if (-not $Values) { return @() }
    return @($Values | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}
$SourcePath     = Split-CommaList $SourcePath
$TestPath       = Split-CommaList $TestPath
$TestClass      = Split-CommaList $TestClass
$ExtraClasspath = Split-CommaList $ExtraClasspath

# ---------------------------------------------------------------------------
# Exit codes
# ---------------------------------------------------------------------------
$EXIT_OK          = 0
$EXIT_TEST_FAILED = 1
$EXIT_INFRA       = 2
$EXIT_BAD_INPUT   = 3
$EXIT_COMPILE     = 4

# ---------------------------------------------------------------------------
# Layout
# ---------------------------------------------------------------------------
$script:ProjectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))

if (-not $BuildDir)     { $BuildDir     = Join-Path $script:ProjectRoot 'build-verify' }
if (-not $GradleCache)  { $GradleCache  = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1' }
if (-not $AndroidJar)   {
    $AndroidJar = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-35\android.jar'
}

$script:BuildDir     = [System.IO.Path]::GetFullPath($BuildDir)
$script:GradleCache  = [System.IO.Path]::GetFullPath($GradleCache)
$script:AndroidJar   = [System.IO.Path]::GetFullPath($AndroidJar)
$script:JavaExe      = $JavaExe

$script:MainClassesDir = Join-Path $script:BuildDir 'classes\main'
$script:TestClassesDir = Join-Path $script:BuildDir 'classes\test'
$script:TmpDir         = Join-Path $script:BuildDir 'tmp'
$script:HomeDir        = Join-Path $script:BuildDir 'home'
$script:AarDir         = Join-Path $script:BuildDir 'aar'
$script:LogFile        = Join-Path $script:BuildDir 'last-run.log'

$script:LogLines = New-Object System.Collections.Generic.List[string]

# ------------------------------- logging -----------------------------------
function Write-Log {
    param([string]$Message = '', [string]$Color = 'Gray')
    $script:LogLines.Add($Message)
    Write-Host $Message -ForegroundColor $Color
}
function Write-Head {
    param([string]$Message)
    Write-Log ''
    Write-Log ('=' * 74) 'Cyan'
    Write-Log $Message 'Cyan'
    Write-Log ('=' * 74) 'Cyan'
}
function Write-Fail {
    param([string]$Message, [int]$Code, [string]$Hint)
    Write-Log ''
    Write-Log "ERROR: $Message" 'Red'
    if ($Hint) { Write-Log "HINT:  $Hint" 'Yellow' }
    Write-Log ''
    Write-Log "ROUTER-TESTS: ERROR (exit $Code)" 'Red'
    Save-Log
    exit $Code
}
function Save-Log {
    try {
        if (-not (Test-Path -LiteralPath $script:BuildDir)) { return }
        Set-Content -LiteralPath $script:LogFile -Value ($script:LogLines -join [Environment]::NewLine) -Encoding utf8
    } catch {
        Write-Host "WARN: could not write log file $($script:LogFile): $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# Path helpers
# ---------------------------------------------------------------------------
function ConvertTo-Absolute {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $Path))
}

# Recreate an output directory from scratch. Deleting the directory itself (rather
# than "$dir\*") is deliberate: -LiteralPath does not expand wildcards, and stale
# compiled classes from an earlier run must never survive into discovery.
function Reset-Directory {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

# Every path the JVM will see: relative when inside the project root, absolute
# otherwise. This is what keeps 'ドキュメント' out of the command line.
function ConvertTo-JavaPath {
    param([string]$AbsolutePath)
    $rel = [System.IO.Path]::GetRelativePath($script:ProjectRoot, $AbsolutePath)
    if ($rel -like '..*' -or [System.IO.Path]::IsPathRooted($rel)) {
        return ($AbsolutePath -replace '\\', '/')
    }
    return ($rel -replace '\\', '/')
}

# ---------------------------------------------------------------------------
# Dependency table (versions mirror gradle/libs.versions.toml)
# ---------------------------------------------------------------------------
$script:ClasspathJarSpecs = @(
    [pscustomobject]@{ Id = 'kotlin-stdlib';               Group = 'org.jetbrains.kotlin';       Name = 'kotlin-stdlib';               Version = '2.0.21';       Pattern = 'kotlin-stdlib-*.jar' }
    [pscustomobject]@{ Id = 'kotlinx-coroutines-core-jvm'; Group = 'org.jetbrains.kotlinx';      Name = 'kotlinx-coroutines-core-jvm'; Version = '1.9.0';        Pattern = 'kotlinx-coroutines-core-jvm-*.jar' }
    [pscustomobject]@{ Id = 'retrofit';                    Group = 'com.squareup.retrofit2';     Name = 'retrofit';                    Version = '2.11.0';       Pattern = 'retrofit-*.jar' }
    [pscustomobject]@{ Id = 'okhttp';                      Group = 'com.squareup.okhttp3';       Name = 'okhttp';                      Version = '4.12.0';       Pattern = 'okhttp-*.jar' }
    [pscustomobject]@{ Id = 'okio-jvm';                    Group = 'com.squareup.okio';          Name = 'okio-jvm';                    Version = '3.6.0';        Pattern = 'okio-jvm-*.jar' }
    [pscustomobject]@{ Id = 'junit';                       Group = 'junit';                      Name = 'junit';                       Version = '4.13.2';       Pattern = 'junit-*.jar' }
    [pscustomobject]@{ Id = 'hamcrest-core';               Group = 'org.hamcrest';               Name = 'hamcrest-core';               Version = '1.3';          Pattern = 'hamcrest-core-*.jar' }
    [pscustomobject]@{ Id = 'annotations';                 Group = 'org.jetbrains';              Name = 'annotations';                 Version = '13.0';         Pattern = 'annotations-*.jar' }
    [pscustomobject]@{ Id = 'androidx.annotation';         Group = 'androidx.annotation';        Name = 'annotation';                  Version = '1.3.0';        Pattern = 'annotation-*.jar' }
)

# Only needed on the compiler's own JVM classpath, never on the compile classpath.
$script:CompilerJarSpecs = @(
    [pscustomobject]@{ Id = 'kotlin-compiler-embeddable';  Group = 'org.jetbrains.kotlin';       Name = 'kotlin-compiler-embeddable';  Version = '2.0.21';       Pattern = 'kotlin-compiler-embeddable-*.jar' }
    [pscustomobject]@{ Id = 'trove4j';                     Group = 'org.jetbrains.intellij.deps'; Name = 'trove4j';                    Version = '1.0.20200330'; Pattern = 'trove4j-*.jar' }
)

# AndroidX ships .aar, not .jar: classes.jar is extracted from inside it.
$script:AarSpecs = @(
    [pscustomobject]@{ Id = 'security-crypto'; Group = 'androidx.security'; Name = 'security-crypto'; Version = '1.1.0-alpha06' }
)

function Get-VersionOfCachedFile {
    param([string]$FilePath)
    # <artifactDir>\<version>\<sha1>\<file>
    return (Split-Path (Split-Path $FilePath -Parent) -Parent | Split-Path -Leaf)
}

function Resolve-CachedArtifactFile {
    param(
        [string]$Id, [string]$Group, [string]$Name, [string]$Version, [string]$Pattern
    )
    $artifactDir = Join-Path $script:GradleCache (Join-Path $Group $Name)
    if (-not (Test-Path -LiteralPath $artifactDir -PathType Container)) {
        throw "$Id is absent from the Gradle cache. Searched: '$artifactDir' (artifact $Group`:$Name`:$Version)."
    }
    $preferredDir = Join-Path $artifactDir $Version
    $files = @()
    if (Test-Path -LiteralPath $preferredDir -PathType Container) {
        $files = @(Get-ChildItem -LiteralPath $preferredDir -Recurse -File -Filter $Pattern -ErrorAction SilentlyContinue)
    }
    $fellBack = $false
    if ($files.Count -eq 0) {
        $files = @(Get-ChildItem -LiteralPath $artifactDir -Recurse -File -Filter $Pattern -ErrorAction SilentlyContinue)
        $fellBack = $true
    }
    $files = @($files | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
    if ($files.Count -eq 0) {
        throw "$Id is absent from the Gradle cache. Searched: '$artifactDir' for '$Pattern' (wanted $Group`:$Name`:$Version)."
    }
    $file = $files | Sort-Object FullName | Select-Object -First 1
    $actual = Get-VersionOfCachedFile $file.FullName
    if ($fellBack -or $actual -ne $Version) {
        Write-Log "NOTICE: $Id resolved to cached version $actual (pinned $Version). Path: $($file.FullName)" 'Yellow'
    }
    return [pscustomobject]@{ Id = $Id; Path = $file.FullName; RequestedVersion = $Version; ActualVersion = $actual }
}

function Resolve-CachedJar { param($Spec) Resolve-CachedArtifactFile -Id $Spec.Id -Group $Spec.Group -Name $Spec.Name -Version $Spec.Version -Pattern $Spec.Pattern }

function Expand-AarClassesJar {
    param([string]$AarPath, [string]$OutPath)
    # Reuse the extracted jar when it is complete. Do NOT compare timestamps:
    # Gradle-built AARs carry a fixed (1979) entry timestamp, so ExtractToFile
    # writes a 1979 mtime and any freshness check would re-extract every run.
    # Comparing the uncompressed entry size is the reliable test.
    $zip = [System.IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        $entry = @($zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' })
        if ($entry.Count -eq 0) { throw "AAR '$AarPath' does not contain classes.jar." }
        if (Test-Path -LiteralPath $OutPath -PathType Leaf) {
            if ((Get-Item -LiteralPath $OutPath).Length -eq $entry[0].Length) { return $false }
        }
        $parent = Split-Path -Parent $OutPath
        if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0], $OutPath, $true)
    } finally {
        $zip.Dispose()
    }
    return $true
}

# ---------------------------------------------------------------------------
# Process execution
# ---------------------------------------------------------------------------
# The JVM can emit UTF-8 (JDK 19+: -Dstdout.encoding) only if we can also decode
# UTF-8 on this side. Probe once; fall back to the JVM default code page.
$script:Utf8Stdio = $false
function Test-Utf8StdioSupport {
    try {
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $script:JavaExe
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
        return $true
    } catch { return $false }
}

$script:LastCommandLine = ''
function Get-JvmArgPrefix {
    $prefix = @(
        '-Dfile.encoding=UTF-8',
        "-Djava.io.tmpdir=$(ConvertTo-JavaPath $script:TmpDir)",
        "-Duser.home=$(ConvertTo-JavaPath $script:HomeDir)"
    )
    if ($script:Utf8Stdio) { $prefix += @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8') }
    return $prefix
}

function Format-CommandLine {
    param([string[]]$Arguments)
    $escaped = foreach ($a in $Arguments) {
        if ($a -match '[\s"]') { '"' + ($a -replace '"', '""') + '"' } else { $a }
    }
    return "$($script:JavaExe) " + ($escaped -join ' ')
}

function Invoke-Java {
    param([string]$Label, [string[]]$Arguments, [switch]$ShowCommand)

    $script:LastCommandLine = Format-CommandLine $Arguments
    Write-Log ''
    Write-Log "--- $Label ---" 'White'
    if ($ShowCommand) { Write-Log $script:LastCommandLine 'DarkGray' }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $script:JavaExe
    $psi.WorkingDirectory       = $script:ProjectRoot     # set via .NET => Unicode-safe
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    if ($script:Utf8Stdio) {
        try {
            $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
            $psi.StandardErrorEncoding  = [System.Text.UTF8Encoding]::new($false)
        } catch { }
    }
    foreach ($a in $Arguments) { [void]$psi.ArgumentList.Add([string]$a) }

    try {
        $proc = [System.Diagnostics.Process]::Start($psi)
    } catch {
        Write-Fail "could not start '$($script:JavaExe)': $($_.Exception.Message)" $EXIT_INFRA `
            "Install a JDK (17+) or pass -JavaExe <path-to-java.exe>."
    }

    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result

    if ($stdout) {
        foreach ($line in ($stdout -split "`r?`n")) { Write-Log $line }
    }
    if ($stderr) {
        foreach ($line in ($stderr -split "`r?`n")) { Write-Log $line 'DarkYellow' }
    }
    return [pscustomobject]@{ ExitCode = $proc.ExitCode; StdOut = $stdout; StdErr = $stderr; Command = $script:LastCommandLine }
}

# ---------------------------------------------------------------------------
# Source expansion
# ---------------------------------------------------------------------------
function Expand-SourceEntries {
    param([string[]]$Entries, [string]$Kind)

    $seen    = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $files   = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]

    foreach ($entry in $Entries) {
        if ([string]::IsNullOrWhiteSpace($entry)) { continue }
        $abs = ConvertTo-Absolute $entry
        if (Test-Path -LiteralPath $abs -PathType Container) {
            $found = @(Get-ChildItem -LiteralPath $abs -Recurse -File -Filter '*.kt' | Sort-Object FullName)
            if ($found.Count -eq 0) { Write-Log "WARN: $Kind directory '$entry' contains no .kt files ($abs)" 'Yellow' }
            foreach ($f in $found) { if ($seen.Add($f.FullName)) { $files.Add($f.FullName) } }
        } elseif (Test-Path -LiteralPath $abs -PathType Leaf) {
            if ($abs -notmatch '\.kt$') { Write-Log "WARN: $Kind entry '$entry' is not a .kt file ($abs)" 'Yellow' }
            if ($seen.Add($abs)) { $files.Add($abs) }
        } else {
            $missing.Add($abs)
        }
    }
    return [pscustomobject]@{ Files = $files; Missing = $missing }
}

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
$defaultSourcePath = @(
    'app/src/main/java/com/seema/assistant/router',
    'app/src/main/java/com/seema/assistant/apikeys/data/ApiProvider.kt',
    'app/src/main/java/com/seema/assistant/apikeys/data/ProviderStatus.kt',
    'app/src/main/java/com/seema/assistant/apikeys/data/ApiKeyVault.kt'
)
$defaultTestPath = 'app/src/test/java/com/seema/assistant/router'

if (-not $SourcePath -or $SourcePath.Count -eq 0) { $SourcePath = $defaultSourcePath }

$testPathSupplied = $PSBoundParameters.ContainsKey('TestPath') -and $TestPath -and $TestPath.Count -gt 0
if (-not $testPathSupplied) {
    $TestPath = @($defaultTestPath)
    # Convenience only, and always announced: if the router test dir does not
    # exist yet, fall back to the whole JVM unit-test tree.
    if (-not (Test-Path -LiteralPath (ConvertTo-Absolute $defaultTestPath))) {
        $broadTestRoot = 'app/src/test/java'
        if (Test-Path -LiteralPath (ConvertTo-Absolute $broadTestRoot)) {
            Write-Log "NOTICE: default test path '$defaultTestPath' does not exist yet; using '$broadTestRoot'." 'Yellow'
            $TestPath = @($broadTestRoot)
        }
    }
}

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
Write-Head 'Seema_Jarvis :: offline Kotlin + JUnit runner (Gradle-free)'
Write-Log "project root     : $($script:ProjectRoot)"
Write-Log "workspace root   : $(Split-Path -Parent $script:ProjectRoot)"
Write-Log "build output     : $($script:BuildDir)"
Write-Log "gradle cache     : $($script:GradleCache)"
Write-Log "android.jar      : $($script:AndroidJar)"
Write-Log "java executable  : $($script:JavaExe)"

# Workspace-only writes: refuse an output root outside the workspace.
$workspaceRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $script:ProjectRoot))
if (-not $script:BuildDir.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    Write-Fail "BuildDir '$($script:BuildDir)' is outside the workspace '$workspaceRoot'." $EXIT_BAD_INPUT `
        'All output must stay inside the workspace (workspace-write sandbox).'
}

foreach ($dir in @($script:BuildDir, $script:MainClassesDir, $script:TestClassesDir, $script:TmpDir, $script:HomeDir, $script:AarDir)) {
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
}

$script:Utf8Stdio = Test-Utf8StdioSupport
Write-Log "utf-8 stdio      : $($script:Utf8Stdio)"

# ---------------------------------------------------------------------------
# Resolve dependencies
# ---------------------------------------------------------------------------
Write-Head 'Resolving jars from the read-only Gradle cache'

$classpathJars = New-Object System.Collections.Generic.List[object]
foreach ($spec in $script:ClasspathJarSpecs) {
    try {
        $classpathJars.Add((Resolve-CachedJar $spec))
    } catch {
        Write-Fail $_.Exception.Message $EXIT_INFRA 'Gradle cannot run here, so the jar must already be in the cache.'
    }
}
$compilerJars = New-Object System.Collections.Generic.List[object]
foreach ($spec in $script:CompilerJarSpecs) {
    try {
        $compilerJars.Add((Resolve-CachedJar $spec))
    } catch {
        Write-Fail $_.Exception.Message $EXIT_INFRA 'Gradle cannot run here, so the jar must already be in the cache.'
    }
}

# AndroidX AARs -> classes.jar inside the workspace
$aarJars = New-Object System.Collections.Generic.List[object]
foreach ($spec in $script:AarSpecs) {
    $aar = $null
    try {
        $aar = Resolve-CachedArtifactFile -Id $spec.Id -Group $spec.Group -Name $spec.Name -Version $spec.Version -Pattern "$($spec.Name)-*.aar"
    } catch {
        Write-Fail $_.Exception.Message $EXIT_INFRA "AndroidX ships .aar; looked for '$($spec.Name)-*.aar' in the cache."
    }
    $outJar = Join-Path $script:AarDir "$($spec.Name)-$($aar.ActualVersion)-classes.jar"
    try {
        $wrote = Expand-AarClassesJar -AarPath $aar.Path -OutPath $outJar
    } catch {
        Write-Fail "could not extract classes.jar from '$($aar.Path)': $($_.Exception.Message)" $EXIT_INFRA $null
    }
    Write-Log ("NOTICE: {0} is an AAR; {1} classes.jar -> {2}" -f $spec.Id, $(if ($wrote) { 'extracted' } else { 'reused' }), $outJar) 'Yellow'
    $aarJars.Add([pscustomobject]@{ Id = $spec.Id; Path = $outJar; RequestedVersion = $spec.Version; ActualVersion = $aar.ActualVersion })
}

foreach ($j in ($classpathJars + $compilerJars + $aarJars)) {
    Write-Log ("  {0,-30} {1,-12} {2}" -f $j.Id, "v$($j.ActualVersion)", $j.Path)
}

if (-not (Test-Path -LiteralPath $script:AndroidJar -PathType Leaf)) {
    Write-Fail "android.jar not found at '$($script:AndroidJar)'." $EXIT_INFRA `
        'Pass -AndroidJar <path> or install Android SDK platform 35.'
}
Write-Log ("  {0,-30} {1,-12} {2}" -f 'android.jar', 'api-35', $script:AndroidJar)

$extraJars = New-Object System.Collections.Generic.List[string]
if ($ExtraClasspath) {
    foreach ($entry in $ExtraClasspath) {
        $abs = ConvertTo-Absolute $entry
        if (-not (Test-Path -LiteralPath $abs -PathType Leaf)) {
            Write-Fail "extra classpath entry not found: '$abs'" $EXIT_BAD_INPUT $null
        }
        $extraJars.Add($abs)
        Write-Log ("  {0,-30} {1,-12} {2}" -f 'extra', '-', $abs)
    }
}
$ExtraClasspath = $extraJars.ToArray()

# ----------------------------- classpaths ----------------------------------
$primaryJars = @($aarJars | ForEach-Object { $_.Path }) + @($classpathJars | ForEach-Object { $_.Path }) + $ExtraClasspath
$compileClasspath = ($primaryJars + $script:AndroidJar)       | ForEach-Object { ConvertTo-JavaPath $_ }
$runtimeClasspath = ($primaryJars)                            | ForEach-Object { ConvertTo-JavaPath $_ }
# K2JVMCompiler's OWN JVM needs kotlin-stdlib (kotlin/jvm/internal/Intrinsics),
# kotlinx-coroutines (IntelliJ core CoroutineScope), org.jetbrains:annotations
# (backend codegen) and trove4j. All of these are cache jars, i.e. pure-ASCII
# absolute paths, so they are safe to put on a command line.
$compilerJvmJars = @($compilerJars | ForEach-Object { $_.Path }) + @($classpathJars | ForEach-Object { $_.Path })
$compilerJvmClasspath = $compilerJvmJars -join ';'

Write-Log ''
Write-Log 'COMPILE CLASSPATH (android.jar included):' 'White'
$i = 0
foreach ($p in $compileClasspath) { $i++; Write-Log ("  [{0}] {1}" -f $i, (ConvertTo-Absolute $p)) }
Write-Log ''
Write-Log 'TEST RUNTIME CLASSPATH (android.jar deliberately excluded: its classes are stubs)' 'White'
$i = 0
foreach ($p in $runtimeClasspath) { $i++; Write-Log ("  [{0}] {1}" -f $i, (ConvertTo-Absolute $p)) }
Write-Log ''
Write-Log 'COMPILER JVM CLASSPATH (java -cp ... K2JVMCompiler):' 'White'
$i = 0
foreach ($p in $compilerJvmJars) { $i++; Write-Log ("  [{0}] {1}" -f $i, $p) }

if ($ListClasspath) {
    Write-Log ''
    Write-Log 'ROUTER-TESTS: CLASSPATH-ONLY (exit 0)' 'Green'
    Save-Log
    exit $EXIT_OK
}

# ---------------------------------------------------------------------------
# Resolve sources
# ---------------------------------------------------------------------------
Write-Head 'Resolving sources'

$src = Expand-SourceEntries -Entries $SourcePath -Kind 'source'
if ($src.Missing.Count -gt 0) {
    Write-Log 'MISSING PRODUCTION SOURCES (searched these absolute paths):' 'Red'
    foreach ($m in $src.Missing) { Write-Log "  - $m" 'Red' }
    Write-Fail "$($src.Missing.Count) production source entr(y/ies) do not exist." $EXIT_BAD_INPUT `
        'The Lead may still be writing them; re-run when they exist, or pass -SourcePath explicitly.'
}
if ($src.Files.Count -eq 0) {
    Write-Fail 'no production .kt files found in -SourcePath.' $EXIT_BAD_INPUT 'Pass -SourcePath <dir-or-file> ...'
}
Write-Log "production sources ($($src.Files.Count)):"
foreach ($f in $src.Files) { Write-Log "  $f" }

$testAbs = Expand-SourceEntries -Entries $TestPath -Kind 'test'
if ($testAbs.Missing.Count -gt 0) {
    Write-Log 'MISSING TEST PATHS (searched these absolute paths):' 'Red'
    foreach ($m in $testAbs.Missing) { Write-Log "  - $m" 'Red' }
    Write-Fail "$($testAbs.Missing.Count) test entr(y/ies) do not exist." $EXIT_BAD_INPUT `
        'Pass -TestPath <dir-or-file> pointing at the tests the Lead wrote.'
}
Write-Log "test sources ($($testAbs.Files.Count)):"
foreach ($f in $testAbs.Files) { Write-Log "  $f" }

$sourceJavaPaths = @($src.Files    | ForEach-Object { ConvertTo-JavaPath $_ })
$testJavaPaths   = @($testAbs.Files | ForEach-Object { ConvertTo-JavaPath $_ })

# ---------------------------------------------------------------------------
# Compile
# ---------------------------------------------------------------------------
$compilerArgs = @(
    '-no-stdlib',
    '-jvm-target', '17',
    '-classpath', ($compileClasspath -join ';')
)

Write-Head 'Compiling production sources (K2JVMCompiler)'
Reset-Directory $script:MainClassesDir
$args1 = @(Get-JvmArgPrefix) + @('-cp', $compilerJvmClasspath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler') + $compilerArgs + @('-d', (ConvertTo-JavaPath $script:MainClassesDir)) + $sourceJavaPaths
$r1 = Invoke-Java -Label 'kotlinc (main)' -Arguments $args1 -ShowCommand
if ($r1.ExitCode -ne 0) {
    Write-Fail "production compilation failed (kotlinc exit $($r1.ExitCode))." $EXIT_COMPILE `
        'Fix the reported Kotlin errors; the full command is in the "kotlinc (main)" section above and in last-run.log.'
}
Write-Log "compile main: OK (exit 0)" 'Green'

if ($testAbs.Files.Count -gt 0) {
    Write-Head 'Compiling test sources (K2JVMCompiler)'
    Reset-Directory $script:TestClassesDir
    $testClasspath = @((ConvertTo-JavaPath $script:MainClassesDir)) + $compileClasspath
    $args2 = @(Get-JvmArgPrefix) + @('-cp', $compilerJvmClasspath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler') +
             @('-no-stdlib', '-jvm-target', '17', '-classpath', ($testClasspath -join ';')) +
             @('-d', (ConvertTo-JavaPath $script:TestClassesDir)) + $testJavaPaths
    $r2 = Invoke-Java -Label 'kotlinc (test)' -Arguments $args2 -ShowCommand
    if ($r2.ExitCode -ne 0) {
        Write-Fail "test compilation failed (kotlinc exit $($r2.ExitCode))." $EXIT_COMPILE `
            'Fix the reported Kotlin errors; the full command is in the "kotlinc (test)" section above and in last-run.log.'
    }
    Write-Log "compile test: OK (exit 0)" 'Green'
}

if ($SkipTests) {
    Write-Head 'RESULT (compile only)'
    Write-Log 'compile main : OK'
    if ($testAbs.Files.Count -gt 0) { Write-Log 'compile test : OK' }
    Write-Log 'tests        : SKIPPED (-SkipTests)'
    Write-Log ''
    Write-Log 'ROUTER-TESTS: COMPILE-OK (tests skipped)' 'Green'
    Save-Log
    exit $EXIT_OK
}

# ---------------------------------------------------------------------------
# Discover test classes
# ---------------------------------------------------------------------------
Write-Head 'Discovering test classes'

if ($TestClass -and $TestClass.Count -gt 0) {
    $classes = @($TestClass | ForEach-Object { $_.Trim() } | Where-Object { $_ })
} else {
    $classes = @(
        Get-ChildItem -LiteralPath $script:TestClassesDir -Recurse -File -Filter '*.class' -ErrorAction SilentlyContinue |
            ForEach-Object {
                $rel = [System.IO.Path]::GetRelativePath($script:TestClassesDir, $_.FullName)
                $fqcn = ($rel -replace '\\', '.') -replace '\.class$', ''
                $simple = ($fqcn -split '\.')[-1]
                if ($simple -notmatch '\$' -and $simple -match '^(Test.*|.*Tests?|.*TestCase)$') { $fqcn }
            } |
            Sort-Object -Unique
    )
}

if ($classes.Count -eq 0) {
    Write-Fail "no test classes discovered under '$($script:TestClassesDir)'." $EXIT_BAD_INPUT `
        'Name test classes *Test/*Tests/TestCase, or pass -TestClass <fully.qualified.Name>.'
}

$unknown = New-Object System.Collections.Generic.List[string]
foreach ($c in $classes) {
    $classFile = Join-Path $script:TestClassesDir (($c -replace '\.', '\') + '.class')
    if (-not (Test-Path -LiteralPath $classFile -PathType Leaf)) { $unknown.Add("$c (expected $classFile)") }
}
if ($unknown.Count -gt 0) {
    Write-Log 'UNKNOWN TEST CLASSES:' 'Red'
    foreach ($u in $unknown) { Write-Log "  - $u" 'Red' }
    Write-Fail "$($unknown.Count) requested test class(es) were not produced by compilation." $EXIT_BAD_INPUT `
        'Check the -TestClass package/name spelling.'
}
Write-Log "test classes to run ($($classes.Count)):"
foreach ($c in $classes) { Write-Log "  $c" }

# ---------------------------------------------------------------------------
# Run JUnit
# ---------------------------------------------------------------------------
Write-Head 'Running JUnit 4 (org.junit.runner.JUnitCore)'

$runtimeCp = @((ConvertTo-JavaPath $script:TestClassesDir), (ConvertTo-JavaPath $script:MainClassesDir)) + $runtimeClasspath
$args3 = @(Get-JvmArgPrefix) + @('-cp', ($runtimeCp -join ';'), 'org.junit.runner.JUnitCore') + $classes
$r3 = Invoke-Java -Label 'JUnitCore' -Arguments $args3 -ShowCommand

$combined = "$($r3.StdOut)`n$($r3.StdErr)"
$testsRun = $null
$testsFailed = $null
if ($combined -match 'OK \((\d+)\s+tests?\)') {
    $testsRun = [int]$Matches[1]; $testsFailed = 0
} elseif ($combined -match 'Tests run:\s*(\d+),\s*Failures:\s*(\d+)') {
    $testsRun = [int]$Matches[1]; $testsFailed = [int]$Matches[2]
}

Write-Head 'RESULT'
Write-Log 'compile main : OK (exit 0)'
Write-Log 'compile test : OK (exit 0)'
Write-Log "JUnitCore    : exit $($r3.ExitCode)"

$finalCode = $EXIT_OK
if ($null -eq $testsRun) {
    Write-Log 'tests        : COULD NOT INTERPRET JUnit output' 'Red'
    Write-Log ''
    Write-Log 'ROUTER-TESTS: FAIL (unparseable test output)' 'Red'
    $finalCode = $EXIT_COMPILE
} else {
    $passed = $testsRun - $testsFailed
    Write-Log "tests        : $testsRun run, $passed passed, $testsFailed failed"
    if ($r3.ExitCode -eq 0 -and $testsFailed -eq 0) {
        Write-Log ''
        Write-Log "ROUTER-TESTS: PASS tests=$testsRun passed=$passed failed=0" 'Green'
        $finalCode = $EXIT_OK
    } else {
        if ($r3.ExitCode -eq 0) {
            Write-Log 'WARN: JUnitCore exited 0 but reported failures; treating as FAIL.' 'Yellow'
        }
        Write-Log ''
        Write-Log "ROUTER-TESTS: FAIL tests=$testsRun passed=$passed failed=$testsFailed" 'Red'
        $finalCode = $EXIT_TEST_FAILED
    }
}

# ---------------------------------------------------------------------------
# Reviewer command
# ---------------------------------------------------------------------------
$scriptPath = Join-Path $PSScriptRoot 'run-router-tests.ps1'
$quotedScript = '"' + $scriptPath + '"'
$explicit = "pwsh -NoProfile -ExecutionPolicy Bypass -File $quotedScript"
$explicit += ' -SourcePath ' + (($SourcePath | ForEach-Object { '"' + $_ + '"' }) -join ',')
$explicit += ' -TestPath '   + (($TestPath   | ForEach-Object { '"' + $_ + '"' }) -join ',')

Write-Log ''
Write-Log 'REVIEWER COMMAND (compile + run the real router tests):' 'Cyan'
Write-Log "  $explicit" 'Cyan'
Write-Log '  (defaults target the router package + apikeys/data deps; override with -SourcePath/-TestPath/-TestClass)' 'DarkGray'
Write-Log "  full log: $($script:LogFile)" 'DarkGray'
Write-Log "EXIT CODE: $finalCode"

Save-Log
exit $finalCode
