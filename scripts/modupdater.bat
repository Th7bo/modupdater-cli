@echo off
REM Pre-launch / post-exit hook for Prism Launcher and Modrinth App on Windows.
REM
REM Takes no path arguments on purpose: Prism does not escape %INST_MC_DIR% and
REM it cannot be quoted in the hook field, so instance paths containing a space
REM break if the value is passed on the hook's command line. It is read here.
REM
REM   Pre-launch:  C:\path\to\modupdater.bat check
REM   Post-exit:   C:\path\to\modupdater.bat apply

setlocal

REM Everything is forwarded, so the same wrapper serves the launcher hooks and
REM someone at a prompt running "modupdater.bat profile use dungeons".
set "ARGS=%*"
if "%ARGS%"=="" set "ARGS=check"

set "HERE=%~dp0"
if "%MODUPDATER_JAR%"=="" (
    set "JAR=%HERE%modupdater-cli.jar"
) else (
    set "JAR=%MODUPDATER_JAR%"
)

if not "%INST_MC_DIR%"=="" (
    set "MODS_DIR=%INST_MC_DIR%\mods"
) else if not "%MODUPDATER_MODS_DIR%"=="" (
    set "MODS_DIR=%MODUPDATER_MODS_DIR%"
) else (
    set "MODS_DIR=%CD%\mods"
)

set "JAVA=java"
if not "%INST_JAVA%"=="" set "JAVA=%INST_JAVA%"

if not exist "%JAR%" (
    echo [modupdater] JAR not found at "%JAR%" - skipping 1>&2
    exit /b 0
)

"%JAVA%" -jar "%JAR%" %ARGS% --mods-dir "%MODS_DIR%"

REM 1 means the user cancelled the launch. Every other failure must still let
REM the game start.
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1
exit /b 0
