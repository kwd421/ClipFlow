$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

python -m pip install -r requirements.txt
python -m unittest discover -s test -p "test_*.py" -v
python -m PyInstaller build-helper\ClipFlow.spec --noconfirm
Copy-Item -Force dist\ClipFlow.exe ClipFlow.exe
