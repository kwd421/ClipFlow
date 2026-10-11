param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$AppExe = (Join-Path $PSScriptRoot '..\dist\ClipFlow.exe'),
    [string]$OutputFile,
    [string]$MakeNsis = $env:CLIPFLOW_MAKENSIS
)
$ErrorActionPreference = 'Stop'
if (-not $OutputFile) {
    $OutputFile = Join-Path $PSScriptRoot "..\dist\ClipFlow-$Version.exe"
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Version must be major.minor.patch' }
if (-not (Test-Path -LiteralPath $AppExe -PathType Leaf)) { throw "Missing app: $AppExe" }
if (-not $MakeNsis) {
    $command = Get-Command makensis -ErrorAction SilentlyContinue
    if ($command) { $MakeNsis = $command.Source }
    else { $MakeNsis = Join-Path ${env:ProgramFiles(x86)} 'NSIS\makensis.exe' }
}
if (-not (Test-Path -LiteralPath $MakeNsis -PathType Leaf)) {
    throw 'NSIS is required. Install NSIS or set CLIPFLOW_MAKENSIS to makensis.exe.'
}
$AppExe = (Resolve-Path -LiteralPath $AppExe).Path
$OutputFile = [IO.Path]::GetFullPath($OutputFile)
& $MakeNsis /V2 "/DAPP_VERSION=$Version" "/DAPP_EXE=$AppExe" "/DOUTPUT_FILE=$OutputFile" (Join-Path $PSScriptRoot 'ClipFlow.nsi')
if ($LASTEXITCODE -ne 0) { throw "NSIS failed: $LASTEXITCODE" }
Write-Host "Built installer $OutputFile"
