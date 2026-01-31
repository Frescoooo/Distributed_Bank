@echo off
REM Run script for Java Banking Server
REM Usage: run.bat [options]
REM Options: --port <port> --lossReq <prob> --lossRep <prob>

echo ========================================
echo   Starting Java Banking Server
echo ========================================
echo.

cd out
java Server %*
