@echo off
setlocal

set BASE_DIR=%~dp0
set MVNW=%BASE_DIR%mvnw.cmd
set JAR=

if not exist "%MVNW%" (
  echo Missing Maven Wrapper at %MVNW% 1>&2
  exit /b 1
)

for %%I in ("%BASE_DIR%target\jaipilot-cli-*-all.jar") do set JAR=%%~fI

if not defined JAR (
  call "%MVNW%" -q -DskipTests package
  if errorlevel 1 exit /b 1
  for %%I in ("%BASE_DIR%target\jaipilot-cli-*-all.jar") do set JAR=%%~fI
)

if not defined JAR (
  echo Could not locate the shaded JAIPilot jar under %BASE_DIR%target 1>&2
  exit /b 1
)

java -jar "%JAR%" %*
