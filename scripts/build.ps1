param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks)
$ErrorActionPreference = 'Stop'
$env:GRADLE_USER_HOME = 'D:\DevTools\WFTools-PC\gradle'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\Administrator\.gradle\caches'
$wixExecutable = Get-ChildItem -LiteralPath 'D:\DevTools\WFTools-PC\wix' -Filter candle.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($wixExecutable) { $env:WIX_PATH = $wixExecutable.DirectoryName; $env:PATH = $wixExecutable.DirectoryName + ';' + $env:PATH }
if (-not $Tasks) { $Tasks = @(':watch-core:test', ':watch-renderer:test', ':desktopApp:test', ':desktopApp:createDistributable') }
Push-Location (Split-Path -Parent $PSScriptRoot)
try { & ./gradlew.bat @Tasks --console=plain; exit $LASTEXITCODE } finally { Pop-Location }
