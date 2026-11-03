param(
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [Parameter(Mandatory = $true)][string]$DataDir,
    [string]$StagingDir = '',
    [int]$ProcessId = 0,
    [string]$Version = '',
    [string]$OldVersion = '',
    [switch]$Recover
)
# Swaps the installed files for the staged ones, waits for the new build to prove it started, and
# restores the previous files when it does not. The parameters come from the app itself, never from a download.
$ErrorActionPreference = 'Stop'
$journal = Join-Path $DataDir 'update-journal.json'
$result = Join-Path $DataDir 'update-result.json'
$rollback = Join-Path $InstallDir '.rollback'
$added = Join-Path $rollback '_added.txt'
$exe = Join-Path $InstallDir 'AngatuVoice.exe'

function Save-Journal($phase) {
    @{ phase = $phase; version = $Version; old = $OldVersion; install = $InstallDir; staging = $StagingDir } |
        ConvertTo-Json | Set-Content -Path $journal -Encoding UTF8
}

function Write-Result($status, $detail) {
    @{ status = $status; version = $Version; old = $OldVersion; detail = $detail; at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() } |
        ConvertTo-Json | Set-Content -Path $result -Encoding UTF8
}

function Move-Retry($from, $to) {
    for ($i = 0; $i -lt 8; $i++) {
        try { Move-Item -LiteralPath $from -Destination $to -Force; return } catch { Start-Sleep -Seconds 1 }
    }
    Move-Item -LiteralPath $from -Destination $to -Force
}

function Restore-Previous {
    if (-not (Test-Path $rollback)) { return }
    if (Test-Path $added) {
        foreach ($name in @(Get-Content $added | Where-Object { $_ -and $_ -notmatch '[\\/]' -and $_ -ne '..' })) {
            $target = Join-Path $InstallDir $name
            if (Test-Path $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        }
    }
    foreach ($item in Get-ChildItem -LiteralPath $rollback -Force | Where-Object { $_.Name -ne '_added.txt' }) {
        $target = Join-Path $InstallDir $item.Name
        if (Test-Path $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        Move-Retry $item.FullName $target
    }
    Remove-Item -LiteralPath $rollback -Recurse -Force
}

function Start-App($arguments) {
    if (Test-Path $exe) { Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $InstallDir }
}

if ($Recover) {
    if (-not (Test-Path $journal)) { exit 0 }
    $state = Get-Content $journal -Raw | ConvertFrom-Json
    $Version = $state.version; $OldVersion = $state.old; $StagingDir = $state.staging
    $marker = Join-Path $DataDir ("startup-ok-" + $Version)
    if ($state.phase -eq 'swapped' -and (Test-Path $marker)) {
        Remove-Item -LiteralPath $rollback -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $journal -Force
        exit 0
    }
    Restore-Previous
    Write-Result 'rolled_back' 'Atualização interrompida; a versão anterior foi restaurada.'
    Remove-Item -LiteralPath $journal -Force
    Start-App '--rolled-back'
    exit 0
}

$marker = Join-Path $DataDir ("startup-ok-" + $Version)
Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
try {
    if ($ProcessId -gt 0) {
        $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if ($process -and -not $process.WaitForExit(60000)) { throw 'O aplicativo não encerrou a tempo.' }
    }
    Save-Journal 'swapping'
    if (Test-Path $rollback) { Remove-Item -LiteralPath $rollback -Recurse -Force }
    New-Item -ItemType Directory -Path $rollback | Out-Null
    $names = @()
    foreach ($item in Get-ChildItem -LiteralPath $StagingDir -Force) {
        $current = Join-Path $InstallDir $item.Name
        if (Test-Path $current) { Move-Retry $current (Join-Path $rollback $item.Name) } else { $names += $item.Name }
        Move-Retry $item.FullName $current
    }
    Set-Content -Path $added -Value $names -Encoding UTF8
    Save-Journal 'swapped'

    $started = Start-Process -FilePath $exe -ArgumentList '--post-update' -WorkingDirectory $InstallDir -PassThru
    $deadline = (Get-Date).AddSeconds(90)
    while (-not (Test-Path $marker)) {
        if ((Get-Date) -gt $deadline) { throw 'A nova versão não confirmou a inicialização.' }
        if ($started.HasExited) { throw 'A nova versão encerrou durante a inicialização.' }
        Start-Sleep -Milliseconds 500
    }
    Remove-Item -LiteralPath $rollback -Recurse -Force -ErrorAction SilentlyContinue
    if ($StagingDir) { Remove-Item -LiteralPath (Split-Path $StagingDir -Parent) -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Result 'ok' 'Atualização concluída.'
    Remove-Item -LiteralPath $journal -Force
} catch {
    $reason = $_.Exception.Message
    if ($started -and -not $started.HasExited) { Stop-Process -Id $started.Id -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2 }
    try {
        Restore-Previous
        Write-Result 'rolled_back' $reason
    } catch {
        Write-Result 'failed' ('Falha ao restaurar: ' + $_.Exception.Message)
    }
    Remove-Item -LiteralPath $journal -Force -ErrorAction SilentlyContinue
    Start-App '--rolled-back'
}
