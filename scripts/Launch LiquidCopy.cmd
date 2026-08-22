@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
set "LAUNCHER=%ROOT%LiquidCopy-Launcher.jar"
set "BUNDLED_JAVA=%ROOT%runtime\bin\java.exe"
set "BUNDLED_JAVAW=%ROOT%runtime\bin\javaw.exe"

if not exist "%LAUNCHER%" goto missing_launcher

if exist "%BUNDLED_JAVA%" if exist "%BUNDLED_JAVAW%" (
  call :validate_java "%BUNDLED_JAVA%"
  if not errorlevel 1 (
    start "LiquidCopy" /D "%ROOT%" "%BUNDLED_JAVAW%" -jar "%LAUNCHER%"
    if errorlevel 1 goto start_failed
    exit /b 0
  )
  echo WARNING: The bundled Java runtime is present but did not validate as Java 21.
  echo          Trying a system Java 21 installation instead.
)

where java.exe >nul 2>nul
if errorlevel 1 goto no_java
call :validate_java "java.exe"
if errorlevel 1 goto wrong_java

where javaw.exe >nul 2>nul
if errorlevel 1 (
  start "LiquidCopy" /D "%ROOT%" java.exe -jar "%LAUNCHER%"
) else (
  start "LiquidCopy" /D "%ROOT%" javaw.exe -jar "%LAUNCHER%"
)
if errorlevel 1 goto start_failed
exit /b 0

:validate_java
set "VERSION_FILE=%TEMP%\liquidcopy-java-%RANDOM%-%RANDOM%.txt"
"%~1" -XshowSettings:properties -version >"%VERSION_FILE%" 2>&1
if errorlevel 1 (
  del /q "%VERSION_FILE%" >nul 2>nul
  exit /b 1
)
%SystemRoot%\System32\findstr.exe /C:"java.specification.version = 21" "%VERSION_FILE%" >nul
set "VALIDATION_RESULT=%ERRORLEVEL%"
del /q "%VERSION_FILE%" >nul 2>nul
exit /b %VALIDATION_RESULT%

:missing_launcher
echo ERROR: LiquidCopy-Launcher.jar is missing from:
echo        %ROOT%
goto visible_failure

:no_java
echo ERROR: The bundled Java 21 runtime is missing or invalid, and no system Java was found.
echo        Re-extract the complete LiquidCopy release ZIP.
goto visible_failure

:wrong_java
echo ERROR: The bundled Java 21 runtime is missing or invalid, and system Java is not Java 21.
echo        Re-extract the complete release ZIP or install Java 21.
goto visible_failure

:start_failed
echo ERROR: Windows could not start LiquidCopy.
echo        Run this file from a fully extracted release and review the message above.

:visible_failure
echo.
pause
exit /b 2
