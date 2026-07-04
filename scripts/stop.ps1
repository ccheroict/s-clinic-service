# Stop the detached s-clinic process started by run.ps1.
# Usage:  .\scripts\stop.ps1
$root = Split-Path $PSScriptRoot -Parent
$pidFile = "$root\target\app.pid"
if (Test-Path $pidFile) {
    $appPid = (Get-Content $pidFile).Trim()
    Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    Write-Host "Stopped s-clinic (PID $appPid)."
} else {
    Write-Host "No app.pid found. Nothing to stop (or it was started another way)."
}
