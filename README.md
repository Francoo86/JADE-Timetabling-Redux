# JADE-TimeTabling
Implementación de una resolución de un problema de timetabling (prototipo) basado en agentes.

# Instalación
1. Descargar el proyecto vía zip o clonar el repositorio directamente con:  
    ```
    git clone https://github.com/Francoo86/Implementaciones-MAS
    ```

2. Instalar [Java](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html) (idealmente versión 17/18)
3. Finalizada la instalación importar las librerías esenciales que son las que están en las carpetas de LibreriaJADE y LibreriaJSON.
4. Ejecutar el proyecto desde algún IDE, puede ser NetBeans, Eclipse o IntelliJ. (Las instrucciones de ejecución están [aquí](#ejecucion))
5. Luego de eso instalar Python de la siguiente pagína: [Python (Ultima version)](https://www.python.org/downloads/)
6. Al momento de instalar Python asegurarse de que esté checkeada la opción de `AGREGAR A PATH`.

    6.1. Luego si la instalación lo pide, reiniciar el computador.

7. Dentro de la carpeta raíz del proyecto ejecutar el comando: `python -m venv venv`
8. Esperar a que se cree la carpeta y ejecutamos el siguiente comando en Powershell: `venv\Scripts\Activate`
9. Ejecutar el siguiente comando:
`pip install -r requeriments.txt`
10. Ya con todos esos pasos ya estaremos finalizando la instalación.

# Empezando
Los archivos de entrada son los que están en la carpeta de agent_input. Los cuales poseen los JSONs mencionados a continuación.

## Estructura de los Archivos JSON

### profesores.json
Este archivo contiene la información de los profesores y sus asignaturas asignadas.

```json
{
    "RUT": "string",
    "Nombre": "string",
    "Turno": number,
    "Asignaturas": [
        {
            "CodigoAsignatura": "string",
            "Nombre": "string", 
            "Nivel": number,
            "Paralelo": "string",
            "Horas": number,
            "Vacantes": number,
            "Campus": "string"
        }
    ]
}
```

### salas.json
Define las salas con sus respectivas caracteristicas.
```json
{
    "Turno": number,
    "Codigo": "string",
    "Capacidad": number,
    "Campus": "string"
}
```

### blocks.json
Contiene la configuración acerca de los bloques.  
Disclaimer: De momento los campus están hardcodeados.
```json
{
    "Campuses": {
        "[NombreCampus]": {
            "morning_blocks": number[],
            "afternoon_blocks": number[],
            "year_preferences": {
                "morning": number[],
                "afternoon": number[]
            },
            "time_blocks": {
                "[numeroBloque]": "string horario"
            }
        }
    },
    "BlocksInfo": {
        "Morning": {
            "Primary": number[],
            "Secondary": number[]
        },
        "Afternoon": {
            "Primary": number[],
            "Secondary": number[]
        }
    }
}
```

# Ejecucion
Ejecutar el archivo `Aplicacion.java` y esperar a que finalize la asignación de todas las salas a los profesores.

Al momento de finalizar todas las negociaciones este soltara 2 archivos en la carpeta `agent_output`.

`Horarios_asignados.json`: El cual contiene todos los horarios asignados con respecto a los profesores.

`Horarios_salas.json`: Contiene la información de todos los horarios asignados a las salas correspondientes.

Ya finalizado eso, cargamos el entorno virtual de python con:  
`venv\Scripts\Activate.ps1`

Nos vamos a la carpeta de `scheduleRepresentation` y ejecutamos los 2 scripts de python y soltarán 2 archivos excel, que contienen los datos de los JSONs mencionados anteriormente. 

Ejecutar con:
* `py exportClassroomScheule.py` para las salas.
* `py exportTeacherSchedule.py` para los profesores.
# Issues / Observaciones
La aplicación no va a finalizar debido a que la GUI de JADE está abierta.