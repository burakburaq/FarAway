param([string[]]$Tasks = @('build'))
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath '.tools/java' -Directory | Select-Object -First 1
if (!$jdk) { throw 'Local Java 25 is missing from .tools/java.' }
$env:JAVA_HOME = $jdk.FullName
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools/gradle-home'
& '.tools/gradle-9.6.0/bin/gradle.bat' @Tasks
exit $LASTEXITCODE
