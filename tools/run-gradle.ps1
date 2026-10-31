# Builds the app with the Gradle distribution already present in the Gradle user home.
#
# Why this exists: the project's `gradle/wrapper/gradle-wrapper.jar` is absent, so `gradlew` cannot
# run, and `C:\Program Files` has no Gradle. The cached distribution below is the exact version
# `gradle/wrapper/gradle-wrapper.properties` asks for (8.9), so a build here matches what a
# developer with an intact wrapper would get.
#
# Usage:  pwsh -File tools/run-gradle.ps1 --offline :app:compileDebugKotlin
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)

$ErrorActionPreference = 'Stop'

$dist = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.9-bin'
$gradleBat = Get-ChildItem $dist -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $gradleBat) {
    throw "Gradle 8.9 is not in $dist; run the build once with network access, or install Gradle."
}

# The project path contains non-ASCII characters, so the JVM is told to read and write paths as
# UTF-8 rather than the platform ANSI code page. Without this, jars under the project tree cannot be
# opened and Gradle reports "Unable to access jarfile".
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'

$projectDir = Split-Path -Parent $PSScriptRoot
Write-Host "gradle:  $gradleBat"
Write-Host "project: $projectDir"

& $gradleBat -p $projectDir --console=plain @GradleArgs
exit $LASTEXITCODE
