import pandas as pd
import matplotlib.pyplot as plt

def load_and_prepare_data():
    # Cargar los archivos CSV
    df_madkit = pd.read_csv('C:/GitHub Codes/Implementaciones-MAS/agent_output/Metricas/DF/DF_madkit.csv')
    iteration10_df = pd.read_csv('C:/GitHub Codes/Implementaciones-MAS/agent_output/Metricas/DF/Iteration10_df.csv')
    mas_metrics = pd.read_csv('C:/GitHub Codes/Implementaciones-MAS/agent_output/Metricas/DF/mas_metrics_2025.csv')
    
    # Preparar los datos para la gráfica
    df_madkit_data = df_madkit['df_avg'].reset_index()
    df_madkit_data.columns = ['Servicios_Registrados', 'Tiempo_Registro']
    df_madkit_data['Fuente'] = 'DF_madkit'
    
    iteration10_data = pd.DataFrame({
        'Servicios_Registrados': range(len(iteration10_df)),
        'Tiempo_Registro': iteration10_df['ResponseTime_ms'],
        'Fuente': 'Iteration10'
    })
    
    mas_metrics_data = pd.DataFrame({
        'Servicios_Registrados': range(len(mas_metrics)),
        'Tiempo_Registro': mas_metrics['df_avg'],
        'Fuente': 'MAS_Metrics'
    })
    
    # Concatenar todos los datos
    all_data = pd.concat([df_madkit_data, iteration10_data, mas_metrics_data])
    
    return all_data

def create_comparison_plot(data):
    # Configurar el estilo de la gráfica
    plt.style.use('default')
    
    # Crear nueva figura
    plt.figure(figsize=(12, 6))
    
    # Mapeo de nombres antiguos a nuevos para la leyenda
    nombre_plataformas = {
        'DF_madkit': 'MadKit',
        'Iteration10': 'Jade',
        'MAS_Metrics': 'SPADES'
    }
    
    # Definir colores específicos
    colors = ['blue', 'orange', 'green']
    
    # Obtener datos únicos para cada fuente
    for i, fuente in enumerate(data['Fuente'].unique()):
        subset = data[data['Fuente'] == fuente]
        plt.plot(subset['Servicios_Registrados'], 
                subset['Tiempo_Registro'], 
                marker='o',
                label=nombre_plataformas[fuente],  # Usar el nuevo nombre solo en la leyenda
                color=colors[i],
                linestyle='-',
                markersize=4)
    
    # Personalizar la gráfica
    plt.title('Comparación de Tiempos de Registro por Servicio', fontsize=14, pad=20)
    plt.xlabel('Servicios Registrados', fontsize=12)
    plt.ylabel('Tiempo de Registro (ms)', fontsize=12)
    plt.grid(True, linestyle='--', alpha=0.7)
    plt.legend(title='Plataformas', bbox_to_anchor=(1.05, 1), loc='upper left')
    
    # Ajustar los márgenes
    plt.tight_layout()
    
    # Guardar la gráfica
    plt.savefig('comparacion_tiempos_registro.png', dpi=300, bbox_inches='tight')
    plt.close()

def main():
    try:
        # Cargar y preparar los datos
        data = load_and_prepare_data()
        
        # Crear la gráfica
        create_comparison_plot(data)
        
        print("La gráfica se ha generado exitosamente como 'comparacion_tiempos_registro.png'")
        
        # Imprimir algunas estadísticas básicas
        print("\nEstadísticas por fuente:")
        nombre_plataformas = {
            'DF_madkit': 'MadKit',
            'Iteration10': 'Jade',
            'MAS_Metrics': 'SPADES'
        }
        for fuente in data['Fuente'].unique():
            subset = data[data['Fuente'] == fuente]
            print(f"\n{nombre_plataformas[fuente]}:")
            print(f"  Promedio: {subset['Tiempo_Registro'].mean():.2f} ms")
            print(f"  Máximo: {subset['Tiempo_Registro'].max():.2f} ms")
            print(f"  Mínimo: {subset['Tiempo_Registro'].min():.2f} ms")
        
    except Exception as e:
        print(f"Error durante la ejecución: {str(e)}")

if __name__ == "__main__":
    main()