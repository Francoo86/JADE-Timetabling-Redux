import json
import pandas as pd
import numpy as np
from typing import Dict, List, Any

def load_json_file(filename: str) -> Dict:
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

def create_capacity_dict(input_salas: List[Dict[str, Any]]) -> Dict[str, int]:
    """Crea un diccionario con la capacidad de cada sala."""
    return {sala['Codigo']: sala['Capacidad'] for sala in input_salas}

def create_vacancies_dict(input_profesores: List[Dict[str, Any]]) -> Dict[str, int]:
    """Crea un diccionario con las vacantes de cada asignatura."""
    vacancies = {}
    for profesor in input_profesores:
        for asignatura in profesor['Asignaturas']:
            codigo = asignatura['CodigoAsignatura']
            nombre = asignatura['Nombre']
            key = f"{nombre}-{codigo}"
            vacancies[key] = asignatura['Vacantes']
    return vacancies

def calculate_occupation_index(horarios_asignados: List[Dict[str, Any]], 
                             capacidades: Dict[str, int],
                             vacantes: Dict[str, int]) -> Dict[str, float]:
    """
    Calcula el índice de ocupación basado en vacantes y capacidad de sala.
    
    Args:
        horarios_asignados: Lista de asignaciones realizadas
        capacidades: Diccionario con capacidad de cada sala
        vacantes: Diccionario con vacantes de cada asignatura
    
    Returns:
        Dict con métricas de ocupación
    """
    results = {
        'ocupacion_promedio': 0.0,
        'ocupacion_por_asignacion': [],
        'detalles': {
            'total_asignaciones': 0,
            'asignaciones_sobre_capacidad': 0,
            'asignaciones_bajo_capacidad': 0
        }
    }
    
    total_indices = 0
    num_asignaciones = 0
    
    # Procesar cada profesor y sus asignaciones
    for profesor in horarios_asignados:
        for asignatura in profesor['Asignaturas']:
            sala = asignatura['Sala']
            nombre = asignatura['Nombre']
            codigo = asignatura.get('CodigoAsignatura', '')
            
            # Obtener capacidad de la sala y vacantes de la asignatura
            capacidad_sala = capacidades.get(sala, 0)
            key_asignatura = f"{nombre}-{codigo}"
            vacantes_asignatura = vacantes.get(key_asignatura, 0)
            
            if capacidad_sala > 0 and vacantes_asignatura > 0:
                # Calcular índice de ocupación
                indice = (vacantes_asignatura / capacidad_sala) * 100
                
                # Registrar detalles de la asignación
                asignacion_info = {
                    'sala': sala,
                    'asignatura': nombre,
                    'codigo': codigo,
                    'capacidad_sala': capacidad_sala,
                    'vacantes': vacantes_asignatura,
                    'indice_ocupacion': round(indice, 2)
                }
                results['ocupacion_por_asignacion'].append(asignacion_info)
                
                # Actualizar contadores
                total_indices += indice
                num_asignaciones += 1
                
                # Contabilizar sobre/bajo capacidad
                if indice > 100:
                    results['detalles']['asignaciones_sobre_capacidad'] += 1
                else:
                    results['detalles']['asignaciones_bajo_capacidad'] += 1
    
    # Calcular promedio global
    results['ocupacion_promedio'] = round(total_indices / num_asignaciones 
                                        if num_asignaciones > 0 else 0, 2)
    results['detalles']['total_asignaciones'] = num_asignaciones
    
    return results

def save_results(results: Dict, output_file: str = 'metricas_ocupacion.json') -> None:
    """Guarda los resultados en un archivo JSON."""
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print(f"\nResultados guardados exitosamente en {output_file}")
    except Exception as e:
        print(f"Error al guardar los resultados: {str(e)}")

def main():
    # 1. Cargar datos
    input_salas = load_json_file('agent_input/inputOfSala.json')
    input_profesores = load_json_file('agent_input/inputOfProfesores.json')
    horarios_asignados = load_json_file('agent_output/Horarios_asignados.json')
    
    if not all([input_salas, input_profesores, horarios_asignados]):
        print("Error al cargar los archivos necesarios")
        return
    
    # 2. Crear diccionarios de capacidades y vacantes
    capacidades = create_capacity_dict(input_salas)
    vacantes = create_vacancies_dict(input_profesores)
    
    # 3. Calcular índices
    results = calculate_occupation_index(horarios_asignados, capacidades, vacantes)
    
    # 4. Mostrar resultados
    print("\nResultados del análisis de ocupación:")
    print(f"Ocupación promedio global: {results['ocupacion_promedio']}%")
    print(f"\nTotal de asignaciones: {results['detalles']['total_asignaciones']}")
    print(f"Asignaciones sobre capacidad: {results['detalles']['asignaciones_sobre_capacidad']}")
    print(f"Asignaciones bajo capacidad: {results['detalles']['asignaciones_bajo_capacidad']}")
    
    print("\nDetalle de algunas asignaciones:")
    for asignacion in results['ocupacion_por_asignacion'][:5]:  # Mostrar primeros 5 ejemplos
        print(f"\nSala {asignacion['sala']}:")
        print(f"  Asignatura: {asignacion['asignatura']} ({asignacion['codigo']})")
        print(f"  Capacidad sala: {asignacion['capacidad_sala']}")
        print(f"  Vacantes: {asignacion['vacantes']}")
        print(f"  Índice ocupación: {asignacion['indice_ocupacion']}%")
    
    # 5. Guardar resultados
    save_results(results)

if __name__ == "__main__":
    main()