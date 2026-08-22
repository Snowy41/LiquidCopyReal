param([string]$MinecraftDirectory)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$launcher = Join-Path $root 'LiquidCopy-Launcher.jar'
if (-not (Test-Path -LiteralPath $launcher)) { throw "Missing $launcher" }
$javaArgs = @('-jar', $launcher, '--install')
if ($MinecraftDirectory) { $javaArgs += @('--minecraft-dir', $MinecraftDirectory) }
& java @javaArgs
exit $LASTEXITCODE
