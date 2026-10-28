# Pushes the whole project (the GitHub zip, or a folder) to a GitHub repository as one commit,
# without git: it uses GitHub's API (blobs, tree, commit, branch). Files already on the branch are
# replaced; files only on GitHub are kept. Needs a token with Contents: read and write on that repo.
#
# Run it yourself:
#   powershell -ExecutionPolicy Bypass -File D:\YCC\tools\push-to-github.ps1 -Repo owner/repo -TokenFile D:\path\to\token.txt
# Options: -Source D:\YCC-github.zip (default; a folder works too), -Message "commit text".
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$TokenFile,
    [string]$Source = "D:\YCC-github.zip",
    [string]$Message = "Update from YCC-github.zip"
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$line = Get-Content $TokenFile | Where-Object { $_ -match '(ghp_|gho_|github_pat_)[A-Za-z0-9_]+' } | Select-Object -First 1
if (-not $line) { throw "No GitHub token found in $TokenFile" }
$token = [regex]::Match($line, '(ghp_|gho_|github_pat_)[A-Za-z0-9_]+').Value
$headers = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "X-GitHub-Api-Version" = "2022-11-28" }
$api = "https://api.github.com/repos/$Repo"

function Api($method, $path, $body) {
    $json = if ($body) { $body | ConvertTo-Json -Depth 6 -Compress } else { $null }
    for ($try = 1; $try -le 4; $try++) {
        try {
            if ($json) { return Invoke-RestMethod -Method $method -Uri "$api$path" -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType "application/json; charset=utf-8" }
            return Invoke-RestMethod -Method $method -Uri "$api$path" -Headers $headers
        } catch {
            if ($try -eq 4) { throw }
            Start-Sleep -Seconds ([Math]::Pow(2, $try))
        }
    }
}

# 1. The files: unpack the zip (its top folder "ycc/" is the repository root) or use the folder.
$root = $Source
if ($Source -match '\.zip$') {
    $tmp = Join-Path $env:TEMP ("ycc-push-" + [guid]::NewGuid().ToString("N"))
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($Source, $tmp)
    $root = $tmp
    $inner = Get-ChildItem $tmp -Directory
    if ($inner.Count -eq 1 -and (Get-ChildItem $tmp -File).Count -eq 0) { $root = $inner[0].FullName }
}
$files = Get-ChildItem $root -Recurse -File
Write-Host "$($files.Count) files from $root"

# 2. Where the branch is now.
$info = Api GET "" $null
$branch = $info.default_branch
$head = $null
try { $head = (Api GET "/git/ref/heads/$branch" $null).object.sha } catch { }
if (-not $head) { throw "The repository has no commits yet: add any file (e.g. README) on GitHub first, then run this again." }
$baseTree = (Api GET "/git/commits/$head" $null).tree.sha

# 3. Upload each file as a blob.
$entries = @()
$i = 0
foreach ($f in $files) {
    $i++
    $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
    if ($f.Length -gt 95MB) { Write-Warning "Skipped (over GitHub's 100 MB limit): $rel"; continue }
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.FullName))
    $blob = Api POST "/git/blobs" @{ content = $b64; encoding = "base64" }
    $mode = if ($rel -eq "gradlew" -or $rel -like "*.sh") { "100755" } else { "100644" }
    $entries += @{ path = $rel; mode = $mode; type = "blob"; sha = $blob.sha }
    if ($i % 25 -eq 0) { Write-Host "  uploaded $i / $($files.Count)" }
}

# 4. One tree, one commit, move the branch.
Write-Host "Making the commit ..."
$tree = Api POST "/git/trees" @{ base_tree = $baseTree; tree = $entries }
$commit = Api POST "/git/commits" @{ message = $Message; tree = $tree.sha; parents = @($head) }
Api PATCH "/git/refs/heads/$branch" @{ sha = $commit.sha; force = $false } | Out-Null
if ($tmp) { Remove-Item $tmp -Recurse -Force }
Write-Host ""
Write-Host "Done: $($entries.Count) files in commit $($commit.sha.Substring(0, 7)) on $branch"
Write-Host "  https://github.com/$Repo"
