@echo off
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" goto repair
if not exist ".venv\Scripts\pythonw.exe" goto repair
".venv\Scripts\python.exe" -c "import sys" >nul 2>nul
if errorlevel 1 goto repair
".venv\Scripts\python.exe" -m pip --version >nul 2>nul
if errorlevel 1 goto repair
".venv\Scripts\python.exe" -c "import cv2, PIL, keyring, sounddevice, pyaarlo, curl_cffi" >nul 2>nul
if errorlevel 1 goto repair
exit /b 0

:repair
echo El entorno privado de Python falta o esta danado.
echo Camaras Toapanta intentara repararlo ahora.
call "INSTALAR_UNA_VEZ.bat"
if errorlevel 1 exit /b 1

".venv\Scripts\python.exe" -c "import sys" >nul 2>nul
if errorlevel 1 (
  echo La reparacion no termino correctamente.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" -m pip --version >nul 2>nul
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -c "import cv2, PIL, keyring, sounddevice, pyaarlo, curl_cffi" >nul 2>nul
if errorlevel 1 exit /b 1
exit /b 0
