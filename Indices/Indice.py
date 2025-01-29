import json
from pathlib import Path
from Compactacion import analyze_room_compactness, calculate_global_compactness
from SobreCapacidad import calculate_occupation_index, create_capacity_dict, create_vacancies_dict
from RE import get_unique_courses, calculate_room_eligibility
from RO import create_occupancy_matrix, calculate_ro
from TE import calculate_te

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

def calculate_global_indices():
    """Calcula todos los índices globales y retorna un diccionario con los resultados."""
    
    # Establecer rutas de archivos
    input_dir = Path('agent_input')
    output_dir = Path('agent_output')
    
    # Cargar archivos necesarios
    horarios_salas = load_json_file(output_dir / 'Horarios_salas.json')
    input_salas = load_json_file(input_dir / 'InputOfSala.json')
    input_profesores = load_json_file(input_dir / 'InputOfProfesores.json')
    horarios_asignados = load_json_file(output_dir / 'Horarios_asignados.json')

    if not all([horarios_salas, input_salas, input_profesores, horarios_asignados]):
        print("Error: No se pudieron cargar todos los archivos necesarios")
        return None

    try:
        # 1. Calcular índice de ocupación (SobreCapacidad.py)
        capacidades = create_capacity_dict(input_salas)
        vacantes = create_vacancies_dict(input_profesores)
        ocupacion_results = calculate_occupation_index(horarios_asignados, capacidades, vacantes)
        ocupacion = ocupacion_results['ocupacion_promedio']

        # 2. Calcular índice de compactación (Compactacion.py)
        compactness_stats = analyze_room_compactness(horarios_salas)
        compactacion = calculate_global_compactness(compactness_stats)

        # 3. Calcular Room Eligibility (RE.py)
        courses = get_unique_courses(input_profesores)
        re_value, _ = calculate_room_eligibility(courses, input_salas)

        # 4. Calcular Room Occupancy (RO.py)
        occupancy_matrix = create_occupancy_matrix(horarios_salas)
        ro_value = calculate_ro(occupancy_matrix)

        # 5. Calcular Time-slot Eligibility (TE.py)
        te_value = calculate_te(horarios_salas)

        # Crear diccionario de resultados
        indices_globales = {
            "IndicesGlobal": {
                "Ocupacion": round(ocupacion, 4),
                "Compactacion": round(compactacion, 4),
                "Room_Eligibility": round(re_value, 4),
                "Room_Occupancy": round(ro_value, 4),
                "Time_Slot_Eligibility": round(te_value, 4)
            }
        }

        # Guardar resultados en JSON
        with open(output_dir / 'indices_globales.json', 'w', encoding='utf-8') as f:
            json.dump(indices_globales, f, ensure_ascii=False, indent=2)

        print("\nÍndices globales calculados exitosamente")
        return indices_globales

    except Exception as e:
        print(f"Error al calcular índices globales: {str(e)}")
        return None

def main():
    indices = calculate_global_indices()
    if indices:
        print("\nResultados:")
        print("-" * 50)
        for key, value in indices['IndicesGlobal'].items():
            print(f"{key}: {value}")

if __name__ == "__main__":
    main()