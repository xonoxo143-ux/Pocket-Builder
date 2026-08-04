@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_DIR=%APP_HOME%gradle\wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\gradle-wrapper.jar"
set "WRAPPER_URL=https://services.gradle.org/distributions/gradle-9.3.1-wrapper.jar"
set "WRAPPER_SHA256=b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13"

if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
if not exist "%WRAPPER_JAR%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%.part'; $actual=(Get-FileHash '%WRAPPER_JAR%.part' -Algorithm SHA256).Hash.ToLower(); if ($actual -ne '%WRAPPER_SHA256%') { Remove-Item '%WRAPPER_JAR%.part'; throw 'Gradle wrapper checksum mismatch' }; Move-Item '%WRAPPER_JAR%.part' '%WRAPPER_JAR%'"
  if errorlevel 1 exit /b 1
)

java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
