[CmdletBinding()]
param(
    [string]$ListenAddress = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$Port = 8787,
    [switch]$Background
)
$ErrorActionPreference = 'Stop'
$serverRoot = $PSScriptRoot
$stateRoot = Join-Path $serverRoot 'data'
New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
$keyPath = Join-Path $stateRoot 'server-access-key.txt'
if (-not $env:SAHARA_API_KEY) {
    if (-not (Test-Path -LiteralPath $keyPath)) {
        $generatedKey = & python -c 'import secrets; print(secrets.token_urlsafe(32))'
        if ($LASTEXITCODE -ne 0) { throw 'Python 3.12+ is required.' }
        Set-Content -LiteralPath $keyPath -Value $generatedKey.Trim() -NoNewline
    }
    $env:SAHARA_API_KEY = (Get-Content -LiteralPath $keyPath -Raw).Trim()
}
if (-not $env:SAHARA_DATABASE) { $env:SAHARA_DATABASE = Join-Path $stateRoot 'sahara.sqlite3' }
if (-not $env:OPENROUTER_API_KEY) {
    $languageKeyPath = Join-Path $stateRoot 'openrouter-key.dpapi'
    if (Test-Path -LiteralPath $languageKeyPath) {
        $protectedLanguageKey = Get-Content -LiteralPath $languageKeyPath -Raw | ConvertTo-SecureString
        $env:OPENROUTER_API_KEY = [System.Net.NetworkCredential]::new('', $protectedLanguageKey).Password
    }
}
if (-not $env:SAHARA_BROWSER_ENABLED) { $env:SAHARA_BROWSER_ENABLED = 'true' }
if (-not $env:SAHARA_DRIVER_READ_KEY) {
    $driverConfigPath = Join-Path $stateRoot 'driver-connection.dpapi'
    if (Test-Path -LiteralPath $driverConfigPath) {
        $driverSecret = (Get-Content -LiteralPath $driverConfigPath -Raw).Trim() | ConvertTo-SecureString
        $driverConfig = [System.Net.NetworkCredential]::new('', $driverSecret).Password | ConvertFrom-Json
        $env:SAHARA_DRIVER_URL = $driverConfig.url
        $env:SAHARA_DRIVER_READ_KEY = $driverConfig.key
    }
}
if (-not $env:SAHARA_BROWSER_EXECUTABLE) {
    foreach ($browserPath in @('C:\Program Files\Google\Chrome\Application\chrome.exe', 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')) {
        if (Test-Path -LiteralPath $browserPath) { $env:SAHARA_BROWSER_EXECUTABLE = $browserPath; break }
    }
}
$pythonPath = (Get-Command python -ErrorAction Stop).Source
& $pythonPath -c 'import fastapi,uvicorn,httpx,pydantic,bs4,playwright; import sys; assert sys.version_info >= (3,12)'
if ($LASTEXITCODE -ne 0) { throw 'Install the backend and browser dependencies using the README before starting.' }
$serverArgs = @('-m', 'uvicorn', 'sahara.api:app', '--host', $ListenAddress, '--port', "$Port")
if ($Background) {
    $process = Start-Process -FilePath $pythonPath -ArgumentList $serverArgs -WorkingDirectory $serverRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $stateRoot 'server.stdout.log') -RedirectStandardError (Join-Path $stateRoot 'server.stderr.log')
    Set-Content -LiteralPath (Join-Path $stateRoot 'server-process.txt') -Value $process.Id
    Write-Output "Sahara server started as process $($process.Id). Check /health before using it."
} else {
    Push-Location $serverRoot
    try { & $pythonPath @serverArgs } finally { Pop-Location }
}
