# =============================================================================
#  一键构建大肥鱼桌宠 APK
#  用法: powershell -ExecutionPolicy Bypass -File "D:\codex\dafeiyu on phone\build.ps1"
#  参数: -DebugOnly   只出调试包（快一些）
# =============================================================================
param([switch]$DebugOnly)

$ErrorActionPreference = 'Stop'

$base   = 'D:\codex\dafeiyu on phone'
$proj   = Join-Path $base 'DafeiyuPet'
$dist   = Join-Path $base 'dist'

# 环境变量（避免依赖系统 PATH 是否已刷新）
$env:JAVA_HOME        = 'D:\Android\android-studio\jbr'
$env:ANDROID_HOME     = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
$env:GRADLE_USER_HOME = 'D:\Android\.gradle'
$env:Path = "D:\Android\android-studio\jbr\bin;D:\Android\Sdk\build-tools\37.0.0;$env:Path"

if (-not (Test-Path $proj)) { throw "找不到工程目录: $proj" }
Set-Location $proj

function Invoke-Gradle([string]$task) {
    Write-Host "`n>>> gradlew $task" -ForegroundColor Cyan
    & cmd /c "gradlew.bat $task 2>&1" | ForEach-Object {
        if ($_ -match '^e: |error:|What went wrong|BUILD (SUCCESSFUL|FAILED)') {
            Write-Host $_
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "构建失败: $task" }
}

Invoke-Gradle 'assembleDebug'

if (-not $DebugOnly) {
    Invoke-Gradle 'assembleRelease'

    $relDir = Join-Path $proj 'app\build\outputs\apk\release'
    $rel = Get-ChildItem $relDir -Filter *.apk | Select-Object -First 1
    if ($rel) {
        New-Item -ItemType Directory -Force -Path $dist | Out-Null
        $target = Join-Path $dist '大肥鱼桌宠-v1.1-release.apk'
        Copy-Item $rel.FullName $target -Force
        Write-Host "`n正式包已输出:" -ForegroundColor Green
        Write-Host ("  {0}  ({1:N2} MB)" -f $target, ($rel.Length / 1MB))

        # 校验签名
        $apksigner = 'D:\Android\Sdk\build-tools\37.0.0\apksigner.bat'
        if (Test-Path $apksigner) {
            Write-Host "`n签名校验:" -ForegroundColor Cyan
            # apksigner 会往 stderr 打 Java 的 native-access 警告，
            # PowerShell 默认把原生命令的 stderr 当错误抛出，这里临时放宽
            $old = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & $apksigner verify --print-certs $target 2>$null |
                    Select-String 'DN:' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
            } finally {
                $ErrorActionPreference = $old
            }
        }
    }
}

$dbg = Get-ChildItem (Join-Path $proj 'app\build\outputs\apk\debug') -Filter *.apk -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($dbg) {
    Write-Host "`n调试包:" -ForegroundColor Green
    Write-Host ("  {0}  ({1:N2} MB)" -f $dbg.FullName, ($dbg.Length / 1MB))
}

Write-Host "`n完成。" -ForegroundColor Green
