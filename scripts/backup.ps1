<#
.SYNOPSIS
    Takes an encrypted backup of the s-clinic database.

.DESCRIPTION
    Dumps the database, encrypts the dump, and keeps a bounded number of daily,
    weekly and monthly copies.

    The dump is encrypted because it contains the patient record. Note that the
    identity numbers inside it are already encrypted at the column level, so the
    backup passphrase and SCLINIC_DATA_KEY protect different things and losing
    either one loses different data:

      - the backup passphrase alone protects names, dates of birth, phone
        numbers, diagnoses and notes;
      - SCLINIC_DATA_KEY protects the identity numbers inside the dump.

    Both must be stored somewhere other than the server being backed up, and
    somewhere other than the backup target. A backup encrypted with a key kept
    next to it is not a backup.

.NOTES
    Run from a scheduled task. Verify restores with restore-check.ps1 on a
    schedule too: an untested backup is a guess.
#>
[CmdletBinding()]
param(
    # Where to write backups. Should be a different machine or volume from the
    # database: a backup on the same disk does not survive the disk.
    [string]$BackupRoot = $env:SCLINIC_BACKUP_ROOT,

    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 5432 }),
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'sclinic' }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'sclinic' }),

    # Copies kept per tier. Several cycles, so a corruption discovered late can
    # still be stepped back past.
    [int]$KeepDaily = 14,
    [int]$KeepWeekly = 8,
    [int]$KeepMonthly = 12
)

$ErrorActionPreference = 'Stop'

function Require-Value($value, $name, $hint) {
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$name is not set. $hint"
    }
    return $value
}

$BackupRoot = Require-Value $BackupRoot 'SCLINIC_BACKUP_ROOT' `
    'Set it to a directory on a different volume or host from the database.'
$passphrase = Require-Value $env:SCLINIC_BACKUP_PASSPHRASE 'SCLINIC_BACKUP_PASSPHRASE' `
    'Generate one with: openssl rand -base64 48. Store it outside this server and outside the backup target.'
Require-Value $env:PGPASSWORD 'PGPASSWORD' 'Needed by pg_dump for a non-interactive run.' | Out-Null

foreach ($tool in 'pg_dump', 'openssl') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool is not on PATH."
    }
}

# Which tier this run belongs to. One dump is taken and filed once; the tiers are
# retention rules over the same files, not three separate dumps.
$now = Get-Date
$tier = if ($now.Day -eq 1) { 'monthly' }
        elseif ($now.DayOfWeek -eq [DayOfWeek]::Sunday) { 'weekly' }
        else { 'daily' }

$targetDir = Join-Path $BackupRoot $tier
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$stamp = $now.ToString('yyyyMMdd-HHmmss')
$archive = Join-Path $targetDir "sclinic-$stamp.dump.enc"
$checksumFile = "$archive.sha256"

Write-Host "Backing up $DbName to $archive ($tier)"

# Custom format (-Fc): compressed, and restorable selectively, which matters when
# a restore is being used to recover one table rather than the whole database.
#
# Piped straight into openssl so the unencrypted dump never touches the disk.
$tempPassFile = [System.IO.Path]::GetTempFileName()
try {
    # Passed via file rather than on the command line: process arguments are
    # readable by any other process on the machine.
    Set-Content -Path $tempPassFile -Value $passphrase -NoNewline -Encoding ascii

    & pg_dump --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$DbName `
        --format=custom --no-owner --no-privileges `
        | & openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt `
            -pass "file:$tempPassFile" -out $archive

    if ($LASTEXITCODE -ne 0) {
        throw "Backup failed with exit code $LASTEXITCODE"
    }
}
finally {
    Remove-Item $tempPassFile -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $archive) -or (Get-Item $archive).Length -eq 0) {
    throw "Backup produced no output. Not pruning anything."
}

# Checksum alongside, so a restore can tell a corrupted file from a wrong
# passphrase. Without it, both look like the same failure.
(Get-FileHash -Algorithm SHA256 -Path $archive).Hash | Set-Content -Path $checksumFile

$sizeMb = [math]::Round((Get-Item $archive).Length / 1MB, 1)
Write-Host "Wrote $archive ($sizeMb MB)"

# Pruning happens only after a verified write, so a run that failed cannot take
# the older copies down with it.
$keep = @{ daily = $KeepDaily; weekly = $KeepWeekly; monthly = $KeepMonthly }[$tier]
$existing = Get-ChildItem -Path $targetDir -Filter 'sclinic-*.dump.enc' |
    Sort-Object LastWriteTime -Descending

if ($existing.Count -gt $keep) {
    $existing | Select-Object -Skip $keep | ForEach-Object {
        Write-Host "Pruning $($_.Name)"
        Remove-Item $_.FullName -Force
        Remove-Item "$($_.FullName).sha256" -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Done. $tier copies retained: $([math]::Min($existing.Count, $keep))"
