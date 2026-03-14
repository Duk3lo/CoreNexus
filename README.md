# 🌌 CoreNexus

**CoreNexus** es un gestor integral y avanzado para servidores (Wrapper). Está diseñado para automatizar y simplificar la administración de tu servidor mediante la sincronización de mods (CurseForge y GitHub), monitoreo de rendimiento y salud en tiempo real, actualización automática de recursos y vigilancia de archivos locales.

---

## 🚀 Instalación y Auto-Detección (Muy Importante)

Para que CoreNexus funcione de la manera más eficiente, **es altamente recomendable colocar el archivo ejecutable de CoreNexus directamente en la carpeta base o directorio raíz de tu servidor** (junto al archivo `.jar` principal de tu servidor).

### 🔍 ¿Cómo funciona la Auto-Detección?
CoreNexus cuenta con un sistema inteligente de auto-detección (`WorkspaceSetup.applyAutoDetection()`). Al ejecutarse desde la carpeta raíz del servidor, el programa escaneará automáticamente el directorio actual para encontrar la ruta del servidor y el archivo `.jar` ejecutable.
* **Si lo pones en la carpeta del servidor:** No tendrás que configurar rutas manualmente; el programa lo hará por ti.
* **Si lo pones en otra ubicación:** Tendrás que usar los comandos `core-setpathserver` y `core-setjar` para decirle manualmente al programa dónde están los archivos.

---

## 💻 Guía de Comandos

CoreNexus cuenta con una consola interactiva. Si escribes un comando normal (ej. `say Hola`), se enviará directamente al servidor. Si escribes un comando que empiece con `core-` (o comandos de control), CoreNexus lo interceptará y lo procesará internamente.

### 🎮 Comandos de Control del Servidor
Comandos básicos para el ciclo de vida del servidor.
* `start-server` : Inicia el proceso del servidor utilizando la configuración actual.
* `stop-server` : Envía la señal de apagado seguro al servidor.
* `exit-core` : Apaga el servidor de forma segura, detiene todos los monitores y cierra CoreNexus por completo.

### ⚙️ Configuración del Core (`core-config`)
Administra la configuración base del programa.
* `core-status` : Muestra el estado actual del Core (rutas configuradas, JAR seleccionado y Watchers activos).
* `core-reload` : Recarga todos los archivos `.yml`, detiene servicios y aplica los nuevos cambios sin cerrar el programa.
* `core-setjar <nombre.jar>` : Establece manualmente el nombre del archivo ejecutable del servidor.
* `core-setpathserver <ruta>` : Establece manualmente la ruta absoluta de la carpeta del servidor.

### 🔄 Auto-Updater (`core-updater`)
Controla las rutinas de actualización automática en segundo plano.
* `core-updater <enable|disable> <github|curseforge|server|all>` : Activa o desactiva la búsqueda de actualizaciones automáticas para un módulo específico o para todos a la vez.
* `core-updater restart` : Aplica los cambios hechos en el archivo `UpdatesConfig.yml` y reinicia los relojes de búsqueda.

### 🩺 Monitor de Salud (`core-health`)
Supervisa el uso de RAM, CPU y TPS (rendimiento) del servidor para evitar caídas.
* `core-health status` : Muestra un reporte en tiempo real del uso de CPU, Memoria RAM y alertas de TPS.
* `core-health enable <true/false>` : Enciende o apaga el monitor de salud del servidor.

### 📂 Vigilante de Archivos (`core-watcher`)
Monitorea carpetas para enviar archivos nuevos al servidor, sincronizarlos o reiniciar el servidor si un mod es modificado o eliminado (Safe-Delete).
* `core-watcher list` : Muestra una lista de todas las rutas que CoreNexus está vigilando actualmente y su modo de sincronización.
* `core-watcher add <nombre> <ruta>` : Crea una nueva regla de vigilancia hacia una carpeta de destino.
* `core-watcher remove <nombre>` : Elimina una regla de vigilancia.
* `core-watcher enable <nombre> <true|false>` : Pausa o reanuda un Watcher específico sin borrarlo de la configuración.

### 📥 Sincronización CurseForge (`core-curseforge`)
Busca, descarga y mantiene actualizados los mods directamente desde la API de CurseForge.
* `core-curseforge add <id|nombre>` : Busca un mod por su nombre y te da a elegir, o lo instala directamente si le pasas el ID del proyecto.
* `core-curseforge remove <key|id|archivo>` : Elimina el mod del registro de actualizaciones de CoreNexus.
* `core-curseforge sync <key|id|archivo>` : Fuerza la búsqueda de actualizaciones para un mod en específico.
* `core-curseforge sync-all` : Busca e instala actualizaciones para todos los mods registrados de CurseForge.
* `core-curseforge restore <key|id|archivo>` : Restaura la versión anterior de un mod desde la carpeta de backups si la nueva versión causa problemas.

### 🐙 Sincronización GitHub (`core-github`)
Descarga y actualiza plugins, mods o herramientas desde las "Releases" de repositorios de GitHub.
* `core-github add <usuario/repositorio>` : Registra un repositorio para descargar automáticamente su última release (ej. `core-github add EssentialsX/Essentials`).
* `core-github remove <key|slug|archivo>` : Elimina el repositorio de la base de datos de CoreNexus.
* `core-github sync <key|slug|archivo>` : Verifica si hay una nueva versión (Release) en ese repositorio específico y la descarga.
* `core-github sync-all` : Comprueba actualizaciones para todos los repositorios registrados.
* `core-github restore <key|slug|archivo>` : Devuelve el archivo descargado a su versión anterior desde los respaldos.

---

*Desarrollado para la automatización y estabilidad extrema de servidores.*