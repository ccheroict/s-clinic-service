# Run s-clinic detached (background), logs to target\run.log.
# Console returns immediately so you can keep working.
# Usage:  .\scripts\run.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# DB connection (override here or via environment before running)
if (-not $env:DB_URL)      { $env:DB_URL = "jdbc:postgresql://localhost:5432/sclinic" }
if (-not $env:DB_USER)     { $env:DB_USER = "sclinic" }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = "sclinic" }

$jar = Get-ChildItem "$root\target\*.jar" | Where-Object { $_.Name -notlike "*-sources.jar" } | Select-Object -First 1
if (-not $jar) { Write-Error "Jar not found. Run: mvn -DskipTests package"; exit 1 }

$p = Start-Process -FilePath "java" -ArgumentList "-jar", "`"$($jar.FullName)`"" `
        -RedirectStandardOutput "$root\target\run.log" `
        -RedirectStandardError  "$root\target\run.err.log" `
        -PassThru -NoNewWindow
$p.Id | Out-File "$root\target\app.pid"
Write-Host "s-clinic started (PID $($p.Id)). Logs: target\run.log"
Write-Host "Tail logs:  Get-Content target\run.log -Wait"
Write-Host "Stop:       .\scripts\stop.ps1"
