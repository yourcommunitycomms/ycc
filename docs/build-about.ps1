# Rebuilds docs\RECREATE-PROMPT.md (head + PROTOCOL.md) and the app's bundled About text
# (app\src\main\assets\about.txt: attribution + the prompt + UPDATES.md). Run after changing any of them.
$docs = $PSScriptRoot
$enc = New-Object System.Text.UTF8Encoding $false
$head = [IO.File]::ReadAllText("$docs\recreate-prompt-head.md")
$proto = [IO.File]::ReadAllText("$docs\PROTOCOL.md") -replace '(?s)^# [^\n]*\n\s*', ''
$issues = [IO.File]::ReadAllText("$docs\known-issues.md")
$prompt = $head + ($proto -replace '(?m)^## ', '### ') + "`n" + $issues
[IO.File]::WriteAllText("$docs\RECREATE-PROMPT.md", $prompt, $enc)
$serverDocs = Join-Path (Split-Path $docs) 'server\docs'  # the file store's Docs tab serves this copy
New-Item -ItemType Directory -Force $serverDocs | Out-Null
[IO.File]::WriteAllText("$serverDocs\RECREATE-PROMPT.md", $prompt, $enc)
foreach ($f in 'NOMAD-SETUP.md', 'ALL-FEATURES.txt') { Copy-Item "$docs\$f" "$serverDocs\$f" -Force }
$updates = [IO.File]::ReadAllText("$docs\UPDATES.md")
$about = 'This app was created by Claude Code (Anthropic) with the following prompt and updates, based initially off the "Your Community Communications" project.' +
        "`n`n==== PROMPT ====`n`n" + $prompt + "`n`n==== UPDATES SINCE ====`n`n" + $updates
$assets = Join-Path (Split-Path $docs) 'app\src\main\assets'
New-Item -ItemType Directory -Force $assets | Out-Null
[IO.File]::WriteAllText("$assets\about.txt", $about, $enc)
"RECREATE-PROMPT.md and assets\about.txt rebuilt ($($about.Length) chars)"
