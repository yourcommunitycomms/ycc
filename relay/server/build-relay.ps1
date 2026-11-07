# Builds relay\server\ycc-relay.jar: the headless internet relay. It compiles the app's own
# plain-Java tunnel code (net\Tunnel.java, net\RelayAdmin.java, net\Packet.java, crypto\ChannelCrypto.java) together
# with YccRelay.java, so the relay and the app always speak the same frames.
# Needs a JDK 8+ (default D:\YCCtools\jdk17). The jar targets Java 8, so any Java 8+ runs it.
param([string]$Jdk = "D:\YCCtools\jdk17")
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$app = Join-Path (Split-Path (Split-Path $here)) 'app\src\main\java\org\communitycomms\app'
$out = Join-Path $env:TEMP "ycc-relay-build"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null
$src = @("$app\net\Tunnel.java", "$app\net\RelayAdmin.java", "$app\net\Packet.java", "$app\crypto\ChannelCrypto.java",
         "$here\org\communitycomms\relay\YccRelay.java")
& "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -nowarn -d $out @src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
"Main-Class: org.communitycomms.relay.YccRelay`n" | Out-File -Encoding ascii "$out\manifest.txt"
& "$Jdk\bin\jar.exe" cfm "$here\ycc-relay.jar" "$out\manifest.txt" -C $out org
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Remove-Item -Recurse -Force $out
"Built $here\ycc-relay.jar"
