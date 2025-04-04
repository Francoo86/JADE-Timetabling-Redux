import json
import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path

def load_json_file(filename):
    """Carga un archivo JSON y maneja posibles errores."""
    try:
        with open(filename, 'r', encoding='utf-8') as file:
            return json.load(file)
    except FileNotFoundError:
        print(f"Error: No se encontró el archivo {filename}")
        return None
    except json.JSONDecodeError:
        print(f"Error: El archivo {filename} no es un JSON válido")
        return None
    except Exception as e:
        print(f"Error inesperado al cargar {filename}: {str(e)}")
        return None

def get_unique_courses(profesores_data):
    """Extrae todos los cursos únicos con sus requisitos."""
    courses = []
    for profesor in profesores_data:
        for asignatura in profesor['Asignaturas']:
            course = {
                'nombre': asignatura['Nombre'],
                'vacantes': asignatura['Vacantes'],
                'campus': asignatura['Campus']
            }
            # Solo agregar si no existe ya la misma combinación
            if course not in courses:
                courses.append(course)
    return courses

def calculate_room_eligibility(courses, salas):
    """Calcula el Room Eligibility (RE) y estadísticas relacionadas."""
    total_ratio = 0
    eligibility_data = []
    
    for course in courses:
        # Filtrar salas del mismo campus
        campus_rooms = [sala for sala in salas if sala['Campus'] == course['campus']]
        total_rooms = len(campus_rooms)
        
        if total_rooms == 0:
            continue
            
        # Contar salas elegibles (que cumplen con la capacidad requerida)
        eligible_rooms = len([
            sala for sala in campus_rooms 
            if sala['Capacidad'] >= course['vacantes']
        ])
        
        ratio = eligible_rooms / total_rooms
        total_ratio += ratio
        
        eligibility_data.append({
            'curso': course['nombre'],
            'vacantes': course['vacantes'],
            'campus': course['campus'],
            'salas_elegibles': eligible_rooms,
            'total_salas_campus': total_rooms,
            'ratio': ratio
        })
    
    RE = total_ratio / len(courses)
    return RE, eligibility_data

def create_visualization(RE, eligibility_data, output_path):
    """Crea visualizaciones del análisis de Room Eligibility."""
    # Convertir a DataFrame para facilitar el análisis
    df = pd.DataFrame(eligibility_data)
    
    # Crear figura con dos subplots
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 10), height_ratios=[1, 2])
    
    # 1. Gráfico de barras para el RE global
    ax1.bar(['Room Eligibility (RE)'], [RE], color='#00629B')
    ax1.set_ylim(0, 1)
    ax1.set_title('Métrica Global de Room Eligibility')
    ax1.text(0, RE + 0.05, f'{RE:.4f}', ha='center')
    
    # 2. Distribución de ratios de elegibilidad por curso
    df_sorted = df.sort_values('ratio', ascending=True)
    bars = ax2.barh(range(len(df)), df_sorted['ratio'], color='#00629B')
    ax2.set_yticks(range(len(df)))
    ax2.set_yticklabels(df_sorted['curso'])
    ax2.set_xlabel('Ratio de Elegibilidad')
    ax2.set_title('Distribución de Elegibilidad por Curso')
    
    # Ajustar layout y guardar
    plt.tight_layout()
    plt.savefig(output_path)
    plt.close()

def save_results(RE, eligibility_data, output_file):
    """Guarda los resultados en un archivo JSON."""
    results = {
        'room_eligibility': RE,
        'course_eligibility': eligibility_data
    }
    
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

def main():
    # 1. Cargar datos
    input_dir = Path('agent_input')
    output_dir = Path('agent_output')
    output_dir.mkdir(exist_ok=True)
    
    profesores_data = load_json_file(input_dir / 'InputOfProfesores.json')
    salas_data = load_json_file(input_dir / 'InputOfSala.json')
    
    if not profesores_data or not salas_data:
        return
    
    # 2. Obtener cursos únicos
    courses = get_unique_courses(profesores_data)
    print(f"Total de cursos únicos: {len(courses)}")
    print(f"Total de salas: {len(salas_data)}")
    
    # 3. Calcular Room Eligibility
    RE, eligibility_data = calculate_room_eligibility(courses, salas_data)
    print(f"\nRoom Eligibility (RE): {RE:.4f}")
    
    # 4. Crear visualizaciones
    #create_visualization(RE, eligibility_data, output_dir / 'room_eligibility.png')
    
    # 5. Guardar resultados
    #save_results(RE, eligibility_data, output_dir / 'room_eligibility.json')
    
    # 6. Mostrar estadísticas adicionales
    df = pd.DataFrame(eligibility_data)
    print("\nEstadísticas de elegibilidad por campus:")
    print(df.groupby('campus')['ratio'].agg(['mean', 'min', 'max']).round(4))
    
    print("\nAnálisis de Room Eligibility completado exitosamente")

if __name__ == "__main__":
    main()