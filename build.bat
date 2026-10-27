@echo off
setlocal enabledelayedexpansion

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"

set "CACHE_DIR=%ROOT_DIR%.gradle\loom-cache"

if not exist "%CACHE_DIR%" (
    if exist "%USERPROFILE%\Documents\CLIclient\.gradle\loom-cache" (
        echo Copying cached dependencies from %USERPROFILE%\Documents\CLIclient\.gradle\loom-cache
        if not exist "%ROOT_DIR%.gradle" mkdir "%ROOT_DIR%.gradle"
        xcopy /E /I /Y /Q "%USERPROFILE%\Documents\CLIclient\.gradle\loom-cache" "%CACHE_DIR%" >nul
    ) else if exist "%USERPROFILE%\Documents\Open-respect\.gradle\loom-cache" (
        echo Copying cached dependencies from %USERPROFILE%\Documents\Open-respect\.gradle\loom-cache
        if not exist "%ROOT_DIR%.gradle" mkdir "%ROOT_DIR%.gradle"
        xcopy /E /I /Y /Q "%USERPROFILE%\Documents\Open-respect\.gradle\loom-cache" "%CACHE_DIR%" >nul
    ) else if exist "%USERPROFILE%\Documents\Respect-Bpp\.gradle\loom-cache" (
        echo Copying cached dependencies from %USERPROFILE%\Documents\Respect-Bpp\.gradle\loom-cache
        if not exist "%ROOT_DIR%.gradle" mkdir "%ROOT_DIR%.gradle"
        xcopy /E /I /Y /Q "%USERPROFILE%\Documents\Respect-Bpp\.gradle\loom-cache" "%CACHE_DIR%" >nul
    )
)

echo Starting build...
call gradlew.bat --offline assemble
if errorlevel 1 (
    echo Offline build failed, falling back to online build...
    call gradlew.bat assemble
)

if errorlevel 1 (
    echo Build failed.
    exit /b %errorlevel%
)

echo Build outputs in build\libs:
dir /b "%ROOT_DIR%build\libs\*.jar" "%ROOT_DIR%build\libs\*.zip" 2>nul
