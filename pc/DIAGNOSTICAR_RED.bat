@echo off
setlocal
cd /d "%~dp0"
title Diagnostico de red - Camaras Toapanta

call "VERIFICAR_ENTORNO.bat"
if errorlevel 1 exit /b 1

".venv\Scripts\python.exe" "diagnostico_red.py"
echo.
pause
