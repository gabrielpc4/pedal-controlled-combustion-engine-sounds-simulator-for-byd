$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue

if (-not $pythonCommand) {
    throw "Python 3 was not found on PATH. Install Python 3.11 or newer, then run this file again."
}

Push-Location -LiteralPath $projectRoot
try {
    & $pythonCommand.Source "fh6_server.py" @args
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
