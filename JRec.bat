@echo off
rem Starts the packaged build. Relative to this file, so the folder can be moved.
set "EXE=%~dp0dist\JRec\JRec.exe"

if not exist "%EXE%" (
  echo JRec.exe was not found at:
  echo   %EXE%
  echo.
  echo Run build-dist.bat first to produce it.
  pause
  exit /b 1
)

start "" "%EXE%"
