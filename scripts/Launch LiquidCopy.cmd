@echo off
setlocal
set "ROOT=%~dp0"
where javaw >nul 2>nul
if errorlevel 1 (
  java -jar "%ROOT%LiquidCopy-Launcher.jar"
) else (
  start "LiquidCopy" javaw -jar "%ROOT%LiquidCopy-Launcher.jar"
)
