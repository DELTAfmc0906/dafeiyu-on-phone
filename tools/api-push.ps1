# Push files via GitHub Contents API.
# Fallback for when git over 443 is blocked by the network.
#
# Usage: powershell -File api-push.ps1 -ListFile pushlist.txt -Message "msg"
#
# NOTE: string literals here are intentionally ASCII-only.
# PowerShell 5.1 reads .ps1 files as GBK when they have no BOM, which
# corrupts non-ASCII text and can break string terminators.
param(
    [Parameter(Mandatory=$true)][string]$ListFile,
    [Parameter(Mandatory=$true)][string]$Message
)

$ErrorActionPreference = 'Stop'
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
$repo = 'DELTAfmc0906/dafeiyu-on-phone'
$branch = 'main'
$root = 'D:\codex\dafeiyu on phone'

# Read the path list with .NET (see the BOM note above).
$Files = [System.IO.File]::ReadAllLines($ListFile, [System.Text.Encoding]::UTF8) |
    Where-Object { $_.Trim() -ne '' } |
    ForEach-Object { $_.Trim() }
Write-Output ("  list entries: " + $Files.Count)

function Invoke-Gh {
    param([string[]]$GhArgs)
    $out = & $gh @GhArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw ("gh failed: " + ($out -join ' ')) }
    return $out
}

function Post-Json {
    param([string]$Url, [string]$Json)
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $Json, (New-Object System.Text.UTF8Encoding($false)))
    $out = & $gh api $Url --method POST --input $tmp 2>&1
    $code = $LASTEXITCODE
    Remove-Item $tmp -Force
    if ($code -ne 0) { throw ("POST " + $Url + " failed: " + ($out -join ' ')) }
    return $out
}

# Resolve current remote HEAD.
$refJson = Invoke-Gh @('api', "repos/$repo/git/ref/heads/$branch")
$headSha = ($refJson | ConvertFrom-Json).object.sha
$commitJson = Invoke-Gh @('api', "repos/$repo/git/commits/$headSha")
$baseTree = ($commitJson | ConvertFrom-Json).tree.sha
Write-Output ("  remote HEAD: " + $headSha.Substring(0,7) + "  base tree: " + $baseTree.Substring(0,7))

# Create a blob per file, then one tree containing all of them.
$entries = @()
foreach ($rel in $Files) {
    $full = Join-Path $root $rel
    if (-not (Test-Path $full)) { Write-Output ("  skip (missing): " + $rel); continue }
    $bytes = [System.IO.File]::ReadAllBytes($full)
    $b64 = [Convert]::ToBase64String($bytes)
    $payload = @{ content = $b64; encoding = 'base64' } | ConvertTo-Json -Compress
    $blobJson = Post-Json -Url "repos/$repo/git/blobs" -Json $payload
    $blobSha = ($blobJson | ConvertFrom-Json).sha
    $entries += @{ path = ($rel -replace '\\','/'); mode = '100644'; type = 'blob'; sha = $blobSha }
    Write-Output ("  blob: " + $rel + " -> " + $blobSha.Substring(0,7))
}

if ($entries.Count -eq 0) { Write-Output "  nothing to push"; exit 0 }

$treePayload = @{ base_tree = $baseTree; tree = $entries } | ConvertTo-Json -Depth 6 -Compress
$treeJson = Post-Json -Url "repos/$repo/git/trees" -Json $treePayload
$newTree = ($treeJson | ConvertFrom-Json).sha
Write-Output ("  new tree: " + $newTree.Substring(0,7))

$commitPayload = @{ message = $Message; tree = $newTree; parents = @($headSha) } | ConvertTo-Json -Depth 5 -Compress
$newCommitJson = Post-Json -Url "repos/$repo/git/commits" -Json $commitPayload
$newCommit = ($newCommitJson | ConvertFrom-Json).sha
Write-Output ("  new commit: " + $newCommit.Substring(0,7))

$refPayload = @{ sha = $newCommit; force = $false } | ConvertTo-Json -Compress
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $refPayload, (New-Object System.Text.UTF8Encoding($false)))
$refOut = & $gh api "repos/$repo/git/refs/heads/$branch" --method PATCH --input $tmp 2>&1
$code = $LASTEXITCODE
Remove-Item $tmp -Force
if ($code -ne 0) { throw ("ref update failed: " + ($refOut -join ' ')) }
Write-Output ("  OK - pushed to origin/" + $branch)
