param(
    [string]$Version = "",
    [string]$Out = "dist"
)
# Builds the Windows app-image (launcher + app + bundled runtime) and the update package zip.
# Requires JDK 25 with jpackage on PATH (or JAVA_HOME) and a prior `mvn package`.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not $Version) { $Version = ([xml](Get-Content pom.xml)).project.version }
$numeric = ($Version -split '-')[0]
$jar = "target/angatu-voice-$Version.jar"
if (-not (Test-Path $jar)) { throw "Run 'mvn package' first: $jar not found." }

$input = Join-Path $Out 'input'
$image = Join-Path $Out 'image'
Remove-Item -Recurse -Force $input, $image -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $input, $image | Out-Null
Copy-Item $jar $input
Copy-Item -Recurse target/lib (Join-Path $input 'lib')

$modules = 'java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,java.prefs,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.net,java.security.jgss,java.compiler'
jpackage --type app-image --name AngatuVoice --dest $image --input $input `
    --main-jar "angatu-voice-$Version.jar" --main-class br.com.angatusistemas.voice.Main `
    --app-version $numeric --vendor 'Angatu Sistemas' --icon installer/app.ico `
    --add-modules $modules `
    --java-options '--enable-native-access=ALL-UNNAMED' --java-options '-XX:+UseCompactObjectHeaders' `
    --java-options '-XX:MaxRAMPercentage=40' --java-options '-XX:+ExitOnOutOfMemoryError' --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

$zip = Join-Path $Out "AngatuVoice-$Version-win-x64.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $image 'AngatuVoice/*') -DestinationPath $zip -CompressionLevel Optimal
Write-Output "image: $image/AngatuVoice"
Write-Output "zip:   $zip"
