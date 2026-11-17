@echo off
chcp 65001 >nul
title 自研报表与BI平台
echo ============================================
echo   自研报表与BI平台  (Java 8 + Spring Boot 2.7 + Vue 3)
echo ============================================
echo.
echo 正在启动服务，请稍候...
echo 启动后浏览器访问: http://localhost:8088
echo 演示账号: admin / analyst / viewer  密码: 123456
echo 停止服务: 关闭本窗口 或 按 Ctrl+C
echo.

set JAVA_EXE=C:\Program Files\Java\jdk1.8.0_172\bin\java.exe
if not exist "%JAVA_EXE%" set JAVA_EXE=java

cd /d "%~dp0backend\target"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar report-bi-platform-1.0.0.jar --server.port=8088

pause
