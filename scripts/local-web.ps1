param(
    [ValidateSet('Start', 'Stop', 'Status', 'Watch')]
    [string]$Action = 'Start',
    [string]$JavaPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $projectRoot '.local-runtime'
$stopFile = Join-Path $runtime 'stop'
$jar = Join-Path $runtime 'application.jar'
$log = Join-Path $runtime 'supervisor.log'
$mutex = New-Object System.Threading.Mutex($false, 'Local\DoAnTotNghiep-Web-8081')

function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { return $client.ConnectAsync('127.0.0.1', $Port).Wait(1000) -and $client.Connected }
    catch { return $false }
    finally { $client.Dispose() }
}

function Test-Running {
    try { $acquired = $mutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $acquired = $true }
    if ($acquired) { $mutex.ReleaseMutex(); return $false }
    return $true
}

if ($Action -eq 'Status') {
    Write-Output "Supervisor running: $(Test-Running)"
    Write-Output "Web port 8081: $(Test-Port 8081)"
    Write-Output "PostgreSQL port 5432: $(Test-Port 5432)"
    exit
}

if ($Action -eq 'Stop') {
    if (Test-Running) {
        New-Item -ItemType File -Path $stopFile -Force | Out-Null
        for ($i = 0; $i -lt 15 -and (Test-Running); $i++) { Start-Sleep -Seconds 1 }
        if (Test-Running) { throw 'Supervisor has not stopped yet. Check supervisor.log.' }
    }
    Write-Output 'Local web supervisor stopped.'
    exit
}

if (-not $JavaPath -or -not (Test-Path -LiteralPath $JavaPath)) {
    throw 'Supply -JavaPath with the full path to JDK 26 java.exe.'
}
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Missing .local-runtime/application.jar. Follow scripts/README.md to build it.'
}

if ($Action -eq 'Start') {
    if (Test-Running) { Write-Output 'Web supervisor is already running.'; exit }
    if (Test-Port 8081) { throw 'Port 8081 is already in use. No second server was started.' }
    if (-not (Test-Port 5432)) { throw 'Start PostgreSQL on port 5432 first.' }
    if (Test-Path -LiteralPath $stopFile) { Remove-Item -LiteralPath $stopFile }
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Action Watch -JavaPath "{1}"' -f $PSCommandPath, $JavaPath
    Start-Process powershell.exe -WindowStyle Hidden -WorkingDirectory $projectRoot -ArgumentList $arguments | Out-Null
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-Port 8081) { Write-Output 'Web ready: http://localhost:8081/'; exit }
        Start-Sleep -Seconds 1
    }
    throw "Web did not become ready within 60 seconds. Check $runtime logs."
}

try { $locked = $mutex.WaitOne(0) }
catch [System.Threading.AbandonedMutexException] { $locked = $true }
if (-not $locked) { exit }
$child = $null
try {
    $failures = 0
    while (-not (Test-Path -LiteralPath $stopFile)) {
        if (Test-Port 8081) { throw 'Port 8081 is in use by another process.' }
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
        $started = Get-Date
        Add-Content $log "$started Starting application"
        $child = Start-Process -FilePath $JavaPath -WindowStyle Hidden -WorkingDirectory $projectRoot -ArgumentList @('-jar', ('"{0}"' -f $jar)) -RedirectStandardOutput (Join-Path $runtime "$stamp.stdout.log") -RedirectStandardError (Join-Path $runtime "$stamp.stderr.log") -PassThru
        while (-not $child.HasExited -and -not (Test-Path -LiteralPath $stopFile)) { Start-Sleep -Seconds 1 }
        if (Test-Path -LiteralPath $stopFile) { break }
        $child.WaitForExit()
        Add-Content $log "$(Get-Date) Application exited: $($child.ExitCode)"
        if (((Get-Date) - $started).TotalMinutes -ge 5) { $failures = 0 }
        $failures++
        if ($failures -ge 5) { throw 'Application stopped 5 times in short succession. Fix the error in the logs before restarting.' }
        for ($i = 0; $i -lt 5 -and -not (Test-Path -LiteralPath $stopFile); $i++) { Start-Sleep -Seconds 1 }
    }
}
catch { Add-Content $log "$(Get-Date) Supervisor error: $_"; throw }
finally {
    if ($child -and -not $child.HasExited) { $child.Kill(); $child.WaitForExit() }
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
