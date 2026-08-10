@echo off
REM Double-click this to set up ModUpdater.
REM
REM It just starts install.ps1, which does the actual work — batch can't parse
REM the launcher's config files or read a token without echoing it.

setlocal

set "HERE=%~dp0"

if not exist "%HERE%install.ps1" (
    echo Could not find install.ps1 next to this file.
    echo Make sure you extracted the whole zip, not just the .bat.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%install.ps1"

if errorlevel 1 (
    echo.
    echo Setup did not finish. See the messages above.
    pause
)

endlocal
