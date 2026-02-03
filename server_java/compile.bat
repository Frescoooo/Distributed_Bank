@echo off
REM Compile script for Java Banking Server
REM Usage: compile.bat

echo ========================================
echo   Compiling Java Banking Server
echo ========================================

if not exist "out" mkdir out

echo Compiling source files...
javac -source 8 -target 8 -d out src\Protocol.java src\Bank.java src\Server.java

if %errorlevel% neq 0 (
    echo Compilation failed!
    exit /b 1
)

echo.
echo Compilation successful!
echo.
echo To run the server:
echo   cd out
echo   java Server --port 9000
echo.
echo Options:
echo   --port ^<port^>      Server port (default: 9000)
echo   --lossReq ^<prob^>   Request loss probability 0.0-1.0 (default: 0.0)
echo   --lossRep ^<prob^>   Reply loss probability 0.0-1.0 (default: 0.0)
echo.
