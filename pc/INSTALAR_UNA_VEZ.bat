@echo off
setlocal
cd /d "%~dp0"
title Instalador - Camaras Toapanta

where py >nul 2>nul
if errorlevel 1 (
  where python >nul 2>nul
  if errorlevel 1 (
    echo No se encontro Python.
    echo En Windows 7 instale Python 3.8.10 de 64 bits.
    pause
    exit /b 1
  )
  python -c "import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 8) else 1)" >nul 2>nul
  if errorlevel 1 (
    echo El comando python no corresponde a Python 3.8.
    echo En Windows 7 instale Python 3.8.10 de 64 bits.
    pause
    exit /b 1
  )
  set "PY_COMMAND=python"
  set "PY_VERSION="
  goto python_ready
)

set "PY_COMMAND=py"
set "PY_VERSION="
py -3.8 -c "import sys" >nul 2>nul
if not errorlevel 1 set "PY_VERSION=-3.8"
if defined PY_VERSION goto python_ready
py -3.12 -c "import sys" >nul 2>nul
if not errorlevel 1 set "PY_VERSION=-3.12"
if not defined PY_VERSION (
  py -3.11 -c "import sys" >nul 2>nul
  if not errorlevel 1 set "PY_VERSION=-3.11"
)
if not defined PY_VERSION (
  py -3.10 -c "import sys" >nul 2>nul
  if not errorlevel 1 set "PY_VERSION=-3.10"
)
if not defined PY_VERSION (
  python -c "import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 8) else 1)" >nul 2>nul
  if not errorlevel 1 (
    set "PY_COMMAND=python"
    goto python_ready
  )
)
if not defined PY_VERSION (
  echo No se encontro una version compatible de Python.
  echo Use Python 3.8.10 en Windows 7 o Python 3.10-3.12 en Windows moderno.
  pause
  exit /b 1
)

:python_ready
if not exist ".venv\Scripts\python.exe" goto create_venv
if not exist ".venv\Scripts\pythonw.exe" goto repair_venv
".venv\Scripts\python.exe" -c "import sys" >nul 2>nul
if errorlevel 1 goto repair_venv
".venv\Scripts\python.exe" -m pip --version >nul 2>nul
if errorlevel 1 goto repair_venv
goto venv_ready

:create_venv
echo Creando el entorno privado del visor...
%PY_COMMAND% %PY_VERSION% -m venv ".venv"
if errorlevel 1 goto venv_error
goto verify_venv

:repair_venv
echo.
echo Se encontro un entorno de Python incompleto o enlazado a una instalacion antigua.
echo Reparando automaticamente el entorno privado...
%PY_COMMAND% %PY_VERSION% -m venv --clear ".venv"
if errorlevel 1 goto venv_error

:verify_venv
if not exist ".venv\Scripts\python.exe" goto venv_error
if not exist ".venv\Scripts\pythonw.exe" goto venv_error
".venv\Scripts\python.exe" -c "import sys" >nul 2>nul
if errorlevel 1 goto venv_error
".venv\Scripts\python.exe" -m pip --version >nul 2>nul
if not errorlevel 1 goto venv_ready
echo Restaurando el instalador de componentes incluido con Python...
".venv\Scripts\python.exe" -m ensurepip --default-pip >nul
if errorlevel 1 goto venv_error
".venv\Scripts\python.exe" -m pip --version >nul 2>nul
if errorlevel 1 goto venv_error

:venv_ready

echo Instalando los componentes necesarios...
".venv\Scripts\python.exe" -c "import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 8) else 1)" >nul 2>nul
if errorlevel 1 goto modern_python

".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -r "requirements-py38-win7.txt"
if errorlevel 1 (
  echo No se pudieron instalar los componentes para Windows 7.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" -m pip install --disable-pip-version-check --no-deps "pyaarlo==0.8.0.22"
if errorlevel 1 (
  echo No se pudo instalar el acceso Arlo.
  pause
  exit /b 1
)
goto installation_complete

:modern_python
".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -r "requirements.txt"
if errorlevel 1 (
  echo No se pudieron instalar los componentes del visor.
  pause
  exit /b 1
)

:installation_complete
echo.
echo Instalacion terminada. Ya puede abrir CAMARAS_TOAPANTA.bat
pause
exit /b 0

:venv_error
echo.
echo No se pudo crear o reparar el entorno privado de Python.
echo Cierre otras ventanas de Camaras Toapanta y vuelva a ejecutar este archivo.
echo En Windows 7 use Python 3.8.10 de 64 bits.
pause
exit /b 1
