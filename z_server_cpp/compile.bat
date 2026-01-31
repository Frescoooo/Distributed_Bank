@echo off
REM 编译服务器程序
REM 支持 MSVC (cl.exe) 和 MinGW (g++)

cd /d "%~dp0src"

REM 检查是否有MSVC编译器
where cl.exe >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo Using MSVC compiler...
    cl /EHsc /std:c++11 /Fe:udp_server.exe main.cpp bank.cpp protocol.cpp ws2_32.lib
    if %ERRORLEVEL% == 0 (
        echo Compilation successful!
    ) else (
        echo Compilation failed!
        exit /b 1
    )
    goto :end
)

REM 检查是否有MinGW g++
where g++.exe >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo Using MinGW g++ compiler...
    g++ -o udp_server.exe main.cpp bank.cpp protocol.cpp -lws2_32 -std=c++11
    if %ERRORLEVEL% == 0 (
        echo Compilation successful!
    ) else (
        echo Compilation failed!
        exit /b 1
    )
    goto :end
)

REM 如果都没有找到编译器
echo Error: No C++ compiler found!
echo.
echo Please install one of the following:
echo   1. Visual Studio (with C++ support) - provides cl.exe
echo   2. MinGW-w64 - provides g++.exe
echo.
echo Or use Visual Studio IDE to compile the project.
echo.
pause
exit /b 1

:end
pause
