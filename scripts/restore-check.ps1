<#
.SYNOPSIS
    Proves a backup can actually be restored.

.DESCRIPTION
    Decrypts the most recent backup, restores it into a scratch database, and
    checks the result is a working schema with the expected data in it. Drops the
    scratch database afterwards.

    This exists because a backup that has never been restored is a guess. The
    failures it catches are the ones that do not show up at backup time: a
    passphrase that was rotated without updating the schedule, a dump truncated
    by a full disk, a pg_dump version that cannot be read by the pg_restore on
    the recovery host.

    Run it on staging, on a schedule, and read the output. It exits non-zero on
    any failure so a scheduler can alert.

.NOTES
    Never point -DbHost at production. The script refuses the configured
    production database name outright, but the real safeguard is running it
    somewhere else entirely.
#>
[CmdletBinding()]
param(
    [string]$BackupRoot = $env:SCLINIC_BACKUP_ROOT,

    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'sclinic' }),

    # Scratch database. Created and dropped by this script.
    [string]$ScratchDb = "sclinic_restore_check",

    # Which tier to verify. Defaults to the most frequent, since that is the one
    # a real recovery would reach for first.
    [ValidateSet('daily', 'weekly', 'monthly')]
    [string]$Tier = 'daily'
)

$ErrorActionPreference = 'Stop'

if ($ScratchDb -eq $env:DB_NAME -or $ScratchDb -eq 'sclinic') {
    throw "Refusing to restore over '$ScratchDb'. The scratch database must not be the real one."
}

foreach ($tool in 'psql', 'pg_restore', 'openssl') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool is not on PATH."
    }
}

if ([string]::IsNullOrWhiteSpace($BackupRoot)) { throw 'SCLINIC_BACKUP_ROOT is not set.' }
$passphrase = $env:SCLINIC_BACKUP_PASSPHRASE
if ([string]::IsNullOrWhiteSpace($passphrase)) { throw 'SCLINIC_BACKUP_PASSPHRASE is not set.' }
if ([string]::IsNullOrWhiteSpace($env:PGPASSWORD)) { throw 'PGPASSWORD is not set.' }

$latest = Get-ChildItem -Path (Join-Path $BackupRoot $Tier) -Filter 'sclinic-*.dump.enc' `
    -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $latest) { throw "No $Tier backup found under $BackupRoot." }

Write-Host "Verifying $($latest.Name)"
$age = (Get-Date) - $latest.LastWriteTime
Write-Host ("Age: {0:N1} hours" -f $age.TotalHours)
if ($Tier -eq 'daily' -and $age.TotalHours -gt 48) {
    Write-Warning "The newest daily backup is over 48 hours old. The schedule may not be running."
}

# Checksum first, so a corrupted file is reported as corruption rather than as a
# decryption failure.
$checksumFile = "$($latest.FullName).sha256"
if (Test-Path $checksumFile) {
    $expected = (Get-Content $checksumFile).Trim()
    $actual = (Get-FileHash -Algorithm SHA256 -Path $latest.FullName).Hash
    if ($expected -ne $actual) {
        throw "Checksum mismatch: the backup file is corrupted, not merely unreadable."
    }
    Write-Host "Checksum matches."
} else {
    Write-Warning "No checksum file; cannot tell corruption from a wrong passphrase."
}

$plainDump = Join-Path ([System.IO.Path]::GetTempPath()) "sclinic-restore-check-$PID.dump"
$tempPassFile = [System.IO.Path]::GetTempFileName()
$restored = $false

try {
    Set-Content -Path $tempPassFile -Value $passphrase -NoNewline -Encoding ascii

    & openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 `
        -pass "file:$tempPassFile" -in $latest.FullName -out $plainDump
    if ($LASTEXITCODE -ne 0) {
        throw "Decryption failed. The passphrase may have been rotated without updating the schedule."
    }

    & psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=postgres `
        --quiet --command="drop database if exists $ScratchDb" | Out-Null
    & psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=postgres `
        --quiet --command="create database $ScratchDb" | Out-Null
    $restored = $true

    & pg_restore --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$ScratchDb `
        --no-owner --no-privileges $plainDump
    # pg_restore warns about things that do not matter here (missing roles), so a
    # non-zero exit is not by itself a failure. The checks below are what decide.

    Write-Host "`nChecking the restored database"

    function Query($sql) {
        $result = & psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$ScratchDb `
            --quiet --tuples-only --no-align --command=$sql
        if ($LASTEXITCODE -ne 0) { throw "Query failed: $sql" }
        return $result.Trim()
    }

    $failures = @()

    # The schema arrived, and Flyway's own record of it came too. A restore that
    # loses flyway_schema_history cannot be migrated forward afterwards.
    $migrations = [int](Query "select count(*) from flyway_schema_history where success")
    Write-Host "  applied migrations: $migrations"
    if ($migrations -lt 1) { $failures += 'flyway_schema_history is empty or missing' }

    foreach ($table in 'patient', 'staff', 'audit_log', 'audit_chain_head', 'retention_policy') {
        $exists = Query "select count(*) from information_schema.tables where table_name = '$table'"
        if ($exists -ne '1') { $failures += "table $table is missing" }
    }
    Write-Host "  expected tables present"

    # The append-only triggers on audit_log have to survive a restore. If they do
    # not, the recovered system quietly loses the guarantee that the audit trail
    # cannot be rewritten.
    $triggers = [int](Query "select count(*) from pg_trigger where tgrelid = 'audit_log'::regclass and not tgisinternal")
    Write-Host "  audit_log triggers: $triggers"
    if ($triggers -lt 3) { $failures += "audit_log is missing its append-only triggers (found $triggers, expected 3)" }

    $staff = [int](Query "select count(*) from staff")
    Write-Host "  staff rows: $staff"
    if ($staff -lt 1) { $failures += 'no staff rows; the dump may be structure-only' }

    $patients = [int](Query "select count(*) from patient")
    Write-Host "  patient rows: $patients"

    # Identity numbers must still be unreadable in the restored copy. A dump that
    # somehow carried plaintext would be a leak wearing a backup's clothes.
    $plaintextIdentifiers = [int](Query @"
select count(*) from patient
 where national_id_enc is not null
   and national_id_enc not like 'v1:%'
"@)
    if ($plaintextIdentifiers -gt 0) {
        $failures += "$plaintextIdentifiers patient row(s) hold an identifier that is not encrypted"
    } else {
        Write-Host "  identity numbers encrypted"
    }

    if ($failures.Count -gt 0) {
        Write-Host "`nFAILED:" -ForegroundColor Red
        $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 1
    }

    Write-Host "`nRestore verified: $($latest.Name) is recoverable." -ForegroundColor Green
}
finally {
    Remove-Item $plainDump -Force -ErrorAction SilentlyContinue
    Remove-Item $tempPassFile -Force -ErrorAction SilentlyContinue

    if ($restored) {
        & psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=postgres `
            --quiet --command="drop database if exists $ScratchDb" | Out-Null
    }
}
