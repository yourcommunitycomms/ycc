# Publishes the app as a GitHub release, so phones can update from a fixed address:
#   https://github.com/<owner>/<repo>/releases/latest/download/YCC.apk
# Put that address in the app: Settings > Edit update URL (then Update from URL).
#
# Run it yourself (it reads your token; nothing is stored or sent anywhere but GitHub):
#   powershell -ExecutionPolicy Bypass -File D:\YCC\tools\publish-github-release.ps1 -Repo owner/repo -TokenFile D:\path\to\token.txt
# The token needs "Contents: read and write" on that one repository (a fine-grained token limited to it is best).
# The first line of the token file that looks like a GitHub token (ghp_..., github_pat_...) is used.
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$TokenFile,
    [string]$Apk = "D:\YCC\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Aapt = "D:\YCCtools\sdk\build-tools\34.0.0\aapt.exe"
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$token = (Get-Content $TokenFile | Where-Object { $_ -match '(ghp_|gho_|github_pat_)[A-Za-z0-9_]+' } | Select-Object -First 1)
if (-not $token) { throw "No GitHub token found in $TokenFile" }
$token = [regex]::Match($token, '(ghp_|gho_|github_pat_)[A-Za-z0-9_]+').Value

$badging = & $Aapt dump badging $Apk | Select-String "versionName='([^']+)'"
$version = $badging.Matches[0].Groups[1].Value
$code = [regex]::Match(($badging.Line), "versionCode='(\d+)'").Groups[1].Value
if (-not $version) { throw "Could not read the version from $Apk" }
$tag = "v$version"
$headers = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "X-GitHub-Api-Version" = "2022-11-28" }

Write-Host "Creating release $tag on $Repo ..."
$body = @{ tag_name = $tag; name = "Your Community Communications $version"; body = "App version $version (see UPDATES.md)."; make_latest = "true" } | ConvertTo-Json
try {
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" -Headers $headers -Body $body -ContentType "application/json"
} catch {
    if ("$_" -match "already_exists") {
        Write-Host "Release $tag is already on GitHub (nothing to do). Build a newer app first; every build gets a new version."
        exit 0
    }
    if ("$_" -match "not accessible by personal access token") {
        Write-Host "GitHub refused the token: it needs Contents: Read and write on $Repo."
        exit 1
    }
    throw
}

Write-Host "Uploading YCC.apk ..."
$up = $rel.upload_url -replace '\{\?name,label\}', ''
Invoke-RestMethod -Method Post -Uri "$up`?name=YCC.apk" -Headers $headers -InFile $Apk -ContentType "application/vnd.android.package-archive" | Out-Null
"$code`nYCC.apk`n" | Set-Content -Encoding ascii "$env:TEMP\version.txt"
Invoke-RestMethod -Method Post -Uri "$up`?name=version.txt" -Headers $headers -InFile "$env:TEMP\version.txt" -ContentType "text/plain" | Out-Null

Write-Host ""
Write-Host "Done: $($rel.html_url)"
Write-Host "Update address for the app (always the newest release):"
Write-Host "  https://github.com/$Repo/releases/latest/download/YCC.apk"
