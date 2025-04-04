import json
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

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

def create_occupancy_matrix(horarios_salas):
    """
    Crea una matriz de ocupación (períodos x salas)
    donde 1 indica ocupado y 0 vacío
    """
    # Definir períodos y salas
    periodos = ['1', '2', '3', '4', '5', '6', '7', '8', '9']
    salas = [sala['Codigo'] for sala in horarios_salas]
    
    # Crear matriz de ocupación inicializada en 0
    occupancy_matrix = pd.DataFrame(0, 
                                  index=periodos, 
                                  columns=salas)
    
    # Llenar la matriz con datos de ocupación
    for sala in horarios_salas:
        codigo_sala = sala['Codigo']
        for asignatura in sala['Asignaturas']:
            bloque = str(asignatura['Bloque'])
            occupancy_matrix.loc[bloque, codigo_sala] = 1
            
    return occupancy_matrix

def calculate_ro(occupancy_matrix):
    """
    Calcula el Room Occupancy (RO) metric
    RO = suma(ocupaciones) / (num_periodos * num_salas)
    """
    total_ocupaciones = occupancy_matrix.sum().sum()
    num_periodos = len(occupancy_matrix.index)
    num_salas = len(occupancy_matrix.columns)
    
    ro = total_ocupaciones / (num_periodos * num_salas)
    return ro

def calculate_room_occupancy(horarios_salas):
    """Calcula estadísticas de ocupación por sala."""
    stats = {}
    
    for sala in horarios_salas:
        codigo = sala['Codigo']
        bloques_ocupados = len(sala['Asignaturas'])
        total_bloques = 9  # 9 bloques por día
        
        ocupacion = bloques_ocupados / total_bloques
        
        stats[codigo] = {
            'bloques_ocupados': bloques_ocupados,
            'bloques_disponibles': total_bloques - bloques_ocupados,
            'porcentaje_ocupacion': round(ocupacion * 100, 2)
        }
    
    return stats

def create_occupancy_chart(stats):
    """Crea un gráfico de barras de ocupación por sala."""
    # Preparar datos
    df = pd.DataFrame.from_dict(stats, orient='index')
    df_sorted = df.sort_values('porcentaje_ocupacion', ascending=True)
    
    # Crear gráfico
    fig, ax = plt.subplots(figsize=(15, 10))
    
    # Crear barras
    bars = ax.barh(df_sorted.index, df_sorted['porcentaje_ocupacion'], 
                   color='#00629B')
    
    # Configurar aspecto
    ax.set_xlabel('Porcentaje de Ocupación')
    ax.set_title('Ocupación por Sala')
    
    # Añadir etiquetas en las barras
    for i, bar in enumerate(bars):
        width = bar.get_width()
        ax.text(width + 1, bar.get_y() + bar.get_height()/2,
                f'{width:.1f}%',
                va='center')
    
    plt.tight_layout()
    plt.savefig('ocupacion_salas.png')
    plt.close()

def save_results(stats, ro_metric):
    """Guarda los resultados en archivos CSV y JSON."""
    # Guardar estadísticas por sala en CSV
    df_stats = pd.DataFrame.from_dict(stats, orient='index')
    #df_stats.to_csv('estadisticas_ocupacion.csv')
    
    # Guardar todo en JSON
    results = {
        'room_occupancy_metric': ro_metric,
        'stats_by_room': stats
    }
    
    #with open('metricas_ocupacion.json', 'w', encoding='utf-8') as f:
    #    json.dump(results, f, ensure_ascii=False, indent=2)

def main():
    # 1. Cargar datos
    horarios_salas = load_json_file('agent_output/Horarios_salas.json')
    if horarios_salas is None:
        return
    
    # 2. Crear matriz de ocupación
    occupancy_matrix = create_occupancy_matrix(horarios_salas)
    
    # 3. Calcular RO metric
    ro = calculate_ro(occupancy_matrix)
    print(f"\nRoom Occupancy (RO) Metric: {ro:.3f}")
    print(f"Porcentaje de ocupación global: {ro * 100:.1f}%")
    
    # 4. Calcular estadísticas por sala
    stats = calculate_room_occupancy(horarios_salas)
    
    # 5. Crear visualización
    #create_occupancy_chart(stats)
    
    # 6. Guardar resultados
    save_results(stats, ro)
    
    print("\nAnálisis de ocupación completado exitosamente")

if __name__ == "__main__":
    main()