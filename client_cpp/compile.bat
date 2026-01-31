@echo off
REM Compile script for C++ Banking Client
REM Usage: compile.bat
REM Requires: MinGW-w64 or MSVC with cl.exe in PATH

echo ========================================
echo   Compiling C++ Banking Client
echo ========================================
echo.

if not exist "out" mkdir out

REM Try g++ first (MinGW)
echo Checking for g++ compiler...
g++ --version >nul 2>&1
if %errorlevel% equ 0 (
    echo Found g++ compiler
    echo Compiling with g++...
    g++ -std=c++17 -O2 -o out\client.exe src\protocol.cpp src\client.cpp src\main.cpp -lws2_32
    if %errorlevel% neq 0 (
        echo.
        echo ERROR: Compilation failed!
        echo Please check the error messages above.
        exit /b 1
    )
    goto success
)

REM Try cl.exe (MSVC)
echo Checking for cl.exe compiler...
cl >nul 2>&1
if %errorlevel% equ 0 (
    echo Found cl.exe compiler
    echo Compiling with cl.exe...
    cl /EHsc /O2 /Fe:out\client.exe src\protocol.cpp src\client.cpp src\main.cpp ws2_32.lib
    if %errorlevel% neq 0 (
        echo.
        echo ERROR: Compilation failed!
        echo Please check the error messages above.
        exit /b 1
    )
    goto success
)

echo.
echo ERROR: No suitable compiler found!
echo.
echo Please install one of the following:
echo   1. MinGW-w64 - Add the bin directory to your PATH
echo   2. Visual Studio - Run this from a Developer Command Prompt
echo.
echo To check if g++ is available, run: g++ --version
echo To check if cl.exe is available, run: cl
echo.
exit /b 1

:success
echo.
echo ========================================
echo   Compilation successful!
echo ========================================
echo.
echo Executable created: out\client.exe
echo.
echo To run the client:
echo   run.bat --server 127.0.0.1 --port 9000
echo.
echo Options:
echo   --server ^<ip^>      Server IP address (default: 127.0.0.1)
echo   --port ^<port^>      Server port (default: 9000)
echo   --sem ^<semantic^>   atmost or atleast (default: atmost)
echo   --timeout ^<ms^>     Timeout in milliseconds (default: 500)
echo   --retry ^<count^>    Retry count (default: 5)
echo.
