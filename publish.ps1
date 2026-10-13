# =============================================================================
#  发布新版本到 GitHub Releases
#  位置: D:\codex\dafeiyu on phone\publish.ps1
#
#  用法:
#     powershell -ExecutionPolicy Bypass -File publish.ps1 -Version 1.2.1
#     powershell -ExecutionPolicy Bypass -File publish.ps1 -Version 1.2.2 -Notes "修了xxx"
#
#  做了什么:
#     1. 校验版本号与 app/build.gradle.kts 里的一致
#     2. 完整重建 release APK
#     3. 提交并推送源码到 GitHub
#     4. 创建 tag 与 Release，上传 APK 和文档
#
#  前置: 已安装并登录 gh（GitHub CLI）。本机已登录 DELTAfmc0906。
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [switch]$SkipBuild,
    [switch]$SkipPush
)

$ErrorActionPreference = 'Stop'

$base = 'D:\codex\dafeiyu on phone'
$proj = Join-Path $base 'DafeiyuPet'
$dist = Join-Path $base 'dist'
$repo = 'DELTAfmc0906/dafeiyu-on-phone'

$gh = 'C:\Program Files\GitHub CLI\gh.exe'
if (-not (Test-Path $gh)) { throw "找不到 gh: $gh" }

$Version = $Version.Trim().TrimStart('v', 'V')
$tag = "v$Version"
# 资产文件名与仓库现有 Release 保持一致（dafeiyu-pet-vX.Y-release.apk），
# 避免同一仓库里出现两种命名风格。
$apkName = "dafeiyu-pet-v$Version-release.apk"
$apkPath = Join-Path $dist $apkName

# ---------- 1. 校验版本号一致 ----------
Write-Host "`n[1/5] 校验版本号..." -ForegroundColor Cyan
$gradleFile = Join-Path $proj 'app\build.gradle.kts'
$gradleText = [System.IO.File]::ReadAllText($gradleFile, [System.Text.Encoding]::UTF8)
$m = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $m.Success) { throw "读不到 app/build.gradle.kts 里的 versionName" }
$gradleVersion = $m.Groups[1].Value
if ($gradleVersion -ne $Version) {
    throw "版本号不一致：参数是 $Version，但 build.gradle.kts 里是 $gradleVersion。请先改 build.gradle.kts 再发布。"
}
Write-Host "  versionName = $gradleVersion ✓"

# ---------- 2. 构建 ----------
if (-not $SkipBuild) {
    Write-Host "`n[2/5] 完整重建 release APK..." -ForegroundColor Cyan
    $env:JAVA_HOME = 'D:\Android\android-studio\jbr'
    $env:ANDROID_HOME = 'D:\Android\Sdk'
    $env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
    $env:GRADLE_USER_HOME = 'D:\Android\.gradle'
    Push-Location $proj
    try {
        & cmd /c "gradlew.bat clean assembleRelease 2>&1" | ForEach-Object {
            if ($_ -match '^e: |BUILD (SUCCESSFUL|FAILED)|What went wrong') { Write-Host "  $_" }
        }
        if ($LASTEXITCODE -ne 0) { throw "构建失败" }
    } finally { Pop-Location }

    $rel = Get-ChildItem (Join-Path $proj 'app\build\outputs\apk\release') -Filter *.apk |
        Select-Object -First 1
    if (-not $rel) { throw "没找到 release APK" }
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    Copy-Item $rel.FullName $apkPath -Force
    Write-Host ("  产物: {0}  ({1:N2} MB)" -f $apkName, ($rel.Length / 1MB))
} else {
    Write-Host "`n[2/5] 跳过构建（-SkipBuild）" -ForegroundColor Yellow
    if (-not (Test-Path $apkPath)) { throw "跳过构建但找不到产物: $apkPath" }
}

# ---------- 3. 提交并推送源码 ----------
if (-not $SkipPush) {
    Write-Host "`n[3/5] 提交并推送源码..." -ForegroundColor Cyan
    Push-Location $base
    try {
        & git add -A 2>&1 | Out-Null
        $status = & git status --porcelain 2>&1
        if ($status) {
            & git commit -m "release: v$Version" 2>&1 | ForEach-Object { Write-Host "  $_" }
        } else {
            Write-Host "  没有需要提交的改动"
        }
        & git push origin main 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw "推送失败" }
    } finally { Pop-Location }
} else {
    Write-Host "`n[3/5] 跳过推送（-SkipPush）" -ForegroundColor Yellow
}

# ---------- 4. 准备 Release 说明 ----------
Write-Host "`n[4/5] 准备 Release 说明..." -ForegroundColor Cyan
if (-not $Notes) {
    $Notes = @"
## 大肥鱼桌宠 Android 版 v$Version

### 安装
下载下面的 APK 到手机，点击安装（首次需允许"安装未知来源应用"）。
**首次启动必须授予悬浮窗权限**，否则鱼不会出现：设置页 → 授予悬浮窗权限 → 在系统设置里打开「允许显示在其他应用上层」。

### 本版更新
（未填写，可编辑此 Release 补充）
"@
}
$notesFile = Join-Path $env:TEMP "dafeiyu-release-$Version.md"
[System.IO.File]::WriteAllText($notesFile, $Notes, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "  说明已写入临时文件"

# ---------- 5. 创建 Release ----------
Write-Host "`n[5/5] 创建 Release $tag 并上传..." -ForegroundColor Cyan
$existing = & $gh release view $tag --repo $repo 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  Release $tag 已存在，改为上传/覆盖资产" -ForegroundColor Yellow
    & $gh release upload $tag $apkPath --repo $repo --clobber 2>&1 | ForEach-Object { Write-Host "  $_" }
} else {
    & $gh release create $tag $apkPath `
        --repo $repo `
        --title "大肥鱼桌宠 v$Version" `
        --notes-file $notesFile 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "创建 Release 失败" }
}

# 附带文档
foreach ($doc in @(
    (Join-Path $base 'README.md'),
    (Join-Path $base 'docs\功能映射.md')
)) {
    if (Test-Path $doc) {
        & $gh release upload $tag $doc --repo $repo --clobber 2>&1 | Out-Null
        Write-Host "  已附加 $(Split-Path $doc -Leaf)"
    }
}

Write-Host "`n完成 ✓" -ForegroundColor Green
Write-Host "  Release 页面: https://github.com/$repo/releases/tag/$tag"
Write-Host "  APK: $apkPath"
