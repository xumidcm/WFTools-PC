$ErrorActionPreference='Stop'
$cacheRoots=@('D:\DevTools\WFTools-PC\gradle\caches\modules-2\files-2.1','C:\Users\Administrator\.gradle\caches\modules-2\files-2.1')
function Find-Jar([string]$Group,[string]$Artifact,[string]$Version) {
    foreach($root in $cacheRoots) {
        $dir=Join-Path $root "$Group\$Artifact\$Version"
        $jar=Get-ChildItem -LiteralPath $dir -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if($jar){return $jar.FullName}
    }
    throw "Missing cached jar: $Group/$Artifact/$Version"
}
$compiler=Find-Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.3.21'
$stdlib=Find-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.3.21'
$coroutines=Find-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.10.2'
$annotations=Find-Jar 'org.jetbrains' 'annotations' '23.0.0'
$junit=Find-Jar 'junit' 'junit' '4.13.2'
$hamcrest=Find-Jar 'org.hamcrest' 'hamcrest-core' '1.3'
$output=Join-Path (Split-Path -Parent $PSScriptRoot) 'watch-renderer\build\viewport-test-classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$reflect=(Get-ChildItem 'D:\DevTools\WFTools-PC\gradle\wrapper\dists\gradle-8.13-bin' -Filter 'kotlin-reflect-*.jar' -Recurse | Select-Object -First 1).FullName
$compilePath=@($compiler,$stdlib,$coroutines,$annotations,$reflect) -join ';'
$testPath=@($stdlib,$junit,$hamcrest) -join ';'
& java -Xmx512m -cp $compilePath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 -classpath $testPath -d $output watch-renderer/src/main/kotlin/com/ailife/wftools/pc/preview/Viewport.kt watch-renderer/src/test/kotlin/com/ailife/wftools/pc/preview/ViewportTest.kt
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp ($output+';'+$testPath) org.junit.runner.JUnitCore com.ailife.wftools.pc.preview.ViewportTest
exit $LASTEXITCODE
