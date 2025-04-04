import json
import pandas as pd
from collections import defaultdict

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

def count_gaps(periods):
    """
    Cuenta los huecos entre períodos ocupados.
    
    Args:
        periods (list): Lista de períodos ocupados ordenados.
    
    Returns:
        int: Número de huecos encontrados.
    """
    if not periods or len(periods) < 2:
        return 0
    
    gaps = 0
    for i in range(len(periods) - 1):
        # Si hay más de un período de diferencia entre dos clases consecutivas,
        # hay un hueco
        diff = periods[i + 1] - periods[i]
        if diff > 1:
            gaps += diff - 1
            
    return gaps

def analyze_room_compactness(horarios_salas):
    """
    Analiza la compactación del horario para cada sala.
    
    Args:
        horarios_salas (list): Lista de diccionarios con los horarios por sala.
    
    Returns:
        dict: Diccionario con estadísticas de compactación por sala.
    """
    compactness_stats = {}
    
    for sala in horarios_salas:
        codigo = sala['Codigo']
        # Inicializar contadores por día
        horario_por_dia = defaultdict(list)
        
        # Agrupar períodos por día
        for asignatura in sala['Asignaturas']:
            horario_por_dia[asignatura['Dia']].append(asignatura['Bloque'])
            
        # Calcular huecos y períodos ocupados por día
        total_gaps = 0
        total_periods = 0
        
        for dia, periodos in horario_por_dia.items():
            periodos = sorted(set(periodos))  # Eliminar duplicados y ordenar
            gaps = count_gaps(periodos)
            total_gaps += gaps
            total_periods += len(periodos)
            
        # Calcular métrica de compactación
        # Si hay menos de 2 períodos ocupados, la sala está "perfectamente compacta"
        if total_periods <= 1:
            compactness = 1.0  # 100% compacto si hay 0 o 1 período
        else:
            # Invertimos la métrica para que 1 sea perfecta compactación y 0 sea muy fragmentado
            # La fórmula original era: huecos / (períodos - 1)
            # Nueva fórmula: 1 - (huecos / (períodos - 1))
            compactness = 1.0 - (total_gaps / (total_periods - 1))
            
        compactness_stats[codigo] = {
            'total_gaps': float(total_gaps),
            'total_periods': float(total_periods),
            'compactness': float(compactness)
        }
    
    return compactness_stats

def create_summary_table(compactness_stats):
    """
    Crea una tabla resumen de las estadísticas de compactación.
    
    Args:
        compactness_stats (dict): Diccionario con estadísticas de compactación.
    
    Returns:
        pd.DataFrame: DataFrame con el resumen de estadísticas.
    """
    # Crear DataFrame
    df = pd.DataFrame.from_dict(compactness_stats, orient='index')
    
    # Ordenar por compactación (mayor a menor es mejor ahora)
    df_sorted = df.sort_values('compactness', ascending=False)
    
    # Formatear para mostrar
    print("\nResumen de Compactación por Sala:")
    print("\nSala | Total Huecos | Total Períodos | Compactación (%)")
    print("-" * 60)
    
    for idx, row in df_sorted.iterrows():
        # Multiplicamos por 100 para mostrar como porcentaje
        compactness_pct = row['compactness'] * 100
        print(f"{idx:6} | {row['total_gaps']:12.0f} | {row['total_periods']:14.0f} | {compactness_pct:16.2f}")
    
    return df_sorted

def calculate_global_compactness(compactness_stats):
    """
    Calcula la compactación global del horario.
    
    Args:
        compactness_stats (dict): Diccionario con estadísticas de compactación.
    
    Returns:
        float: Índice de compactación global.
    """
    total_gaps = sum(stats['total_gaps'] for stats in compactness_stats.values())
    total_periods = sum(stats['total_periods'] for stats in compactness_stats.values())
    
    if total_periods <= 1:
        return 1.0  # 100% compacto si hay 0 o 1 período en total
    
    # Invertimos la métrica global también
    global_compactness = 1.0 - (total_gaps / (total_periods - 1))
    return global_compactness

def save_results(df_stats, global_compactness):
    """
    Guarda los resultados en archivos CSV y JSON.
    
    Args:
        df_stats (pd.DataFrame): DataFrame con estadísticas por sala.
        global_compactness (float): Índice de compactación global.
    """
    try:
        # Guardar CSV
        # df_stats.to_csv('compactacion_salas.csv')
        
        # Preparar y guardar JSON
        results = {
            'compactacion_por_sala': df_stats.to_dict(orient='index'),
            'compactacion_global': global_compactness
        }
        
        #with open('metricas_compactacion.json', 'w', encoding='utf-8') as f:
        #    json.dump(results, f, ensure_ascii=False, indent=2)
            
        return True
    except Exception as e:
        print(f"Error al guardar los resultados: {str(e)}")
        return False

def main():
    # 1. Cargar datos
    horarios_salas = load_json_file('agent_output/Horarios_salas.json')
    if horarios_salas is None:
        return
    
    # 2. Analizar compactación por sala
    compactness_stats = analyze_room_compactness(horarios_salas)
    
    # 3. Crear tabla resumen
    df_stats = create_summary_table(compactness_stats)
    
    # 4. Calcular compactación global
    global_compactness = calculate_global_compactness(compactness_stats)
    print(f"\nCompactación Global: {global_compactness * 100:.2f}%")
    
    # 5. Guardar resultados
    if save_results(df_stats, global_compactness):
        print("\nAnálisis de compactación completado exitosamente")
    else:
        print("Error al completar el análisis de compactación")

if __name__ == "__main__":
    main()