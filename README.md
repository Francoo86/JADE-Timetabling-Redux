# JADE-Timetabling-Redux

Implementación de un sistema de resolución de timetabling universitario basado en agentes, usando el framework JADE (Java Agent DEvelopment Framework). Los agentes negocian entre sí para asignar salas y horarios a los profesores de forma distribuida.

---

## Requisitos previos

| Herramienta | Versión mínima | Notas |
|---|---|---|
| Java JDK | 21 | El script `.bat` apunta a `C:\Program Files\Java\jdk-21` |
| IntelliJ IDEA | Cualquier edición | Para compilar el proyecto |
| Python | 3.10+ | Para exportar los resultados a Excel |

---

## Instalación

### 1. Clonar el repositorio

```bash
git clone https://github.com/Francoo86/JADE-Timetabling-Redux.git
cd JADE-Timetabling-Redux
```

### 2. Instalar Java JDK 21

Descargar e instalar desde: https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html

Verificar la instalación:
```bash
java -version
```

### 3. Abrir el proyecto en IntelliJ IDEA

1. Abrir IntelliJ IDEA
2. Seleccionar **File > Open** y elegir la carpeta raíz del proyecto
3. IntelliJ detectará automáticamente el módulo `.iml`

### 4. Configurar las librerías en IntelliJ

Las librerías ya están incluidas en la carpeta `lib/`. Solo hay que agregarlas al proyecto:

1. Ir a **File > Project Structure** (`Ctrl+Alt+Shift+S`)
2. Seleccionar **Modules > Dependencies**
3. Hacer clic en **+** > **JARs or Directories**
4. Agregar todos los archivos de `lib/jade/`:
   - `jade.jar`
   - `jadeExamples.jar`
5. Agregar todos los archivos de `lib/json/`:
   - `json-simple-1.1.1.jar`
   - `json-lib-2.4-jdk15.jar`
   - `jackson-core-2.18.0.jar`
   - `jackson-databind-2.18.0.jar`
   - `jackson-annotations-2.18.0.jar`
   - `javax.json-api-1.1-javadoc.jar`
6. Hacer clic en **Apply > OK**

### 5. Compilar el proyecto

Con el proyecto abierto en IntelliJ:

```
Build > Build Project   (Ctrl + F9)
```

Esto genera los `.class` en `out/production/JADE-Timetabling-Redux/`, que es lo que usa el script de ejecución.

> **Si no compilas el proyecto primero, el script `.bat` fallará con `ClassNotFoundException`.**

### 6. Instalar Python y dependencias

**Instalar Python:** https://www.python.org/downloads/

> Al instalar, marcar la opción **"Add Python to PATH"**.

Crear el entorno virtual e instalar dependencias:

```bash
# En la raíz del proyecto
python -m venv venv

# Activar el entorno virtual (PowerShell)
venv\Scripts\Activate

# Instalar dependencias
pip install -r requeriments.txt
```

Las dependencias instaladas son:
- `pandas==2.2.3`
- `openpyxl==3.1.5`
- `numpy==2.1.3`

---

## Estructura del proyecto

```
JADE-Timetabling-Redux/
├── agent_input/
│   ├── scenarios/          # Escenarios de entrada para la simulación
│   │   ├── small/          # Escenario pequeño
│   │   ├── medium/         # Escenario mediano
│   │   └── full/           # Escenario completo
│   └── examples/           # Ejemplos de archivos JSON de entrada
│       ├── profesores.json
│       └── salas.json
├── agent_output/           # Resultados generados (ignorado por git)
├── lib/
│   ├── jade/               # Librerías del framework JADE
│   └── json/               # Librerías para manejo de JSON
├── scheduleRepresentation/ # Scripts Python para exportar a Excel
│   ├── exportTeacherSchedule.py
│   └── exportClassroomSchedule.py
├── src/                    # Código fuente Java
├── jade-exec.bat           # Script de ejecución principal
└── requeriments.txt        # Dependencias Python
```

### Archivos de entrada (por escenario)

Cada escenario dentro de `agent_input/scenarios/` debe contener:

#### `profesores.json`
Contiene la información de los profesores y sus asignaturas:
```json
[
  {
    "RUT": "string",
    "Nombre": "string",
    "Turno": 0,
    "Asignaturas": [
      {
        "CodigoAsignatura": "string",
        "Nombre": "string",
        "Nivel": 0,
        "Paralelo": "string",
        "Horas": 0,
        "Vacantes": 0,
        "Campus": "string"
      }
    ]
  }
]
```

#### `salas.json`
Contiene la información de las salas disponibles:
```json
[
  {
    "Turno": 0,
    "Codigo": "string",
    "Capacidad": 0,
    "Campus": "string"
  }
]
```

---

## Ejecución

### Ejecutar la simulación

Desde la raíz del proyecto usar el script `jade-exec.bat`:

```bash
# Sintaxis
.\jade-exec.bat [iteraciones] [escenario]

# Ejemplos
.\jade-exec.bat                    # 1 iteración, escenario "small" (por defecto)
.\jade-exec.bat 5 small            # 5 iteraciones con escenario pequeño
.\jade-exec.bat 3 medium           # 3 iteraciones con escenario mediano
.\jade-exec.bat 1 full             # 1 iteración con escenario completo
```

Los escenarios disponibles son: `small`, `medium`, `full`.

> El script usa el JDK ubicado en `C:\Program Files\Java\jdk-21`. Si tu instalación está en otra ruta, edita la línea `set JAVA_HOME=` dentro de `jade-exec.bat`.

### Archivos de salida

Al finalizar la simulación, se generan los siguientes archivos en `agent_output/`:

```
agent_output/
└── IterationResults/
    └── [escenario]/
        ├── Horarios_asignados.json     # Horarios asignados por profesor
        ├── Horarios_salas.json         # Horarios asignados por sala
        ├── iteration_log_[timestamp].txt
        ├── iteration_log_[timestamp].json
        └── iteration_summary_[timestamp].json
```

- **`Horarios_asignados.json`**: Horarios de cada profesor con sus asignaturas y bloques asignados.
- **`Horarios_salas.json`**: Ocupación de cada sala por bloque horario.
- **`iteration_summary_[timestamp].json`**: Métricas de rendimiento de las iteraciones (duración promedio, éxitos, fallos, etc.).

### Exportar resultados a Excel

Con la simulación finalizada y el entorno virtual activado:

```bash
# Activar entorno virtual (si no está activo)
venv\Scripts\Activate

# Exportar horarios de salas
cd scheduleRepresentation
py exportClassroomSchedule.py

# Exportar horarios de profesores
py exportTeacherSchedule.py
```

Esto generará archivos `.xlsx` con los horarios en formato tabular.

---

## Solución de problemas

### `ClassNotFoundException: aplicacion.IterativeAplicacion`

El proyecto no está compilado. Abrir IntelliJ IDEA y ejecutar **Build > Build Project** (`Ctrl+F9`).

### `JAVA_HOME` no encontrado o versión incorrecta

Verificar que el JDK 21 esté instalado en `C:\Program Files\Java\jdk-21`. Si está en otra ruta, editar `jade-exec.bat`:
```bat
set JAVA_HOME=C:\ruta\a\tu\jdk
```

### El script Python falla con `ModuleNotFoundError`

El entorno virtual no está activado o las dependencias no están instaladas:
```bash
venv\Scripts\Activate
pip install -r requeriments.txt
```
