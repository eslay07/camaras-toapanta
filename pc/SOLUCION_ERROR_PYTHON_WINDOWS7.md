# Solución de errores de Python y pip en Windows 7

Estos mensajes no son fallos de las cámaras:

```text
No Python at '...\Python312\python.exe'
No module named pip.__main__; 'pip' is a package and cannot be directly executed
```

El primero indica que `.venv` apunta a un Python eliminado. El segundo indica
que una actualización de `pip` quedó incompleta. La versión 1.5.4 detecta y
repara automáticamente ambos estados.

## Procedimiento

1. Cierre todas las ventanas de Cámaras Toapanta.
2. Instale **Python 3.8.10 de 64 bits** si todavía no está instalado. Durante
   la instalación marque **Add Python to PATH**.
3. Descomprima `Camaras_Toapanta_PC_v1.5.4_Windows7_Python38.zip` en una carpeta
   normal, por ejemplo `Documentos\Camaras Toapanta`.
4. Abra `CAMARAS_TOAPANTA.bat`.
5. Si existe un `.venv` roto, la aplicación mostrará que lo está reparando e
   instalará nuevamente los componentes. Espere el mensaje **Instalación
   terminada** y pulse una tecla.
6. Vuelva a abrir `CAMARAS_TOAPANTA.bat`.

También puede iniciar la reparación directamente abriendo
`INSTALAR_UNA_VEZ.bat`.

No copie la carpeta `.venv` entre computadoras. El ZIP oficial no la contiene
y cada PC crea la suya con el Python instalado localmente.

La configuración de cámaras y la sesión guardada se mantienen en
`%APPDATA%\CamarasToapanta`; la reparación no las elimina.
