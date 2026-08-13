@echo off
setlocal
cd /d "%~dp0"

call "VERIFICAR_ENTORNO.bat"
if errorlevel 1 exit /b 1

start "" ".venv\Scripts\pythonw.exe" "seguridad_casa.py"
exit /b 0
