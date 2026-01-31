@echo off
REM Compile script for Java Banking Client
REM Usage: compile.bat

echo ========================================
echo   Compiling Java Banking Client
echo ========================================

if not exist "out" mkdir out

echo Compiling source files...
javac -d out src\Protocol.java src\Cli.java src\Main.java

if %errorlevel% neq 0 (
    echo Compilation failed!
    exit /b 1
)

echo.
echo Compilation successful!
echo.
echo To run the client:
echo   cd out
echo   java Main --server 127.0.0.1 --port 9000
echo.
echo Options:
echo   --server ^<ip^>      Server IP address (default: 127.0.0.1)
echo   --port ^<port^>      Server port (default: 9000)
echo   --sem ^<semantic^>   atmost or atleast (default: atmost)
echo   --timeout ^<ms^>     Timeout in milliseconds (default: 500)
echo   --retry ^<count^>    Retry count (default: 5)
echo.
