@echo off
REM Run script for C++ Banking Client
REM Usage: run.bat [options]

if not exist "out\client.exe" (
    echo ERROR: Executable not found!
    echo Please compile first by running: compile.bat
    echo.
    exit /b 1
)

echo ========================================
echo   Starting C++ Banking Client
echo ========================================
echo.

out\client.exe %*
