import pandas as pd
import numpy as np
import json

def create_weekly_schedule_pandas(data):
    """
    Creates a weekly schedule using pandas DataFrame
    """
    # Define time blocks
    time_blocks = {
        1: "08:00 - 09:00",
        2: "09:15 - 10:15",
        3: "10:30 - 11:30",
        4: "11:45 - 12:45",
        5: "12:50 - 13:50",
        6: "13:55 - 14:55",
        7: "15:00 - 16:00",
        8: "16:15 - 17:15",
        9: "17:30 - 18:30",
    }
    
    # Define days
    days = ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes']
    
    # Create empty DataFrame
    schedule_df = pd.DataFrame(
        index=time_blocks.values(),
        columns=days,
    )
    
    # Fill schedule
    for student in data:
        for subject in student['Asignaturas']:
            time_slot = time_blocks[subject['Bloque']]
            day = subject['Dia']
            
            # Create formatted cell content
            content = (f"{subject['Nombre']}\n"
                      f"Room: {subject['Sala']}\n"
                      f"Student: {student['Nombre']}\n"
                      f"Satisfaction: {subject['Satisfaccion']}/10")
            
            schedule_df.at[time_slot, day] = content
    
    # Fill NaN with empty string
    schedule_df = schedule_df.fillna('')
    
    return schedule_df

def save_styled_schedule(schedule_df, filename='weekly_schedule.xlsx'):
    """
    Saves the schedule DataFrame to Excel with styling
    """
    # Create a Styler object
    styler = schedule_df.style
    
    # Add background color to non-empty cells with room-based coloring
    def background_color(val):
        if pd.isna(val) or val == '':
            return ''
        if 'Room: A-1' in str(val):
            return 'background-color: #e6f3ff'  # Light blue for Room A-1
        return 'background-color: #e6ffe6'  # Light green for Room B-2
    
    # Apply styles
    styler = (styler.applymap(background_color)
                    .set_properties(**{
                        'border': '1px solid black',
                        'padding': '10px',
                        'text-align': 'center',
                        'white-space': 'pre-wrap',
                        'height': '100px'  # Make cells taller
                    })
                    .set_table_styles([
                        {'selector': 'th',
                         'props': [('background-color', '#CCE5FF'),
                                 ('font-weight', 'bold'),
                                 ('text-align', 'center'),
                                 ('border', '1px solid black'),
                                 ('padding', '10px')]
                        }
                    ]))
    
    # Save to Excel with adjusted column widths
    with pd.ExcelWriter(filename, engine='openpyxl') as writer:
        styler.to_excel(writer, index=True, sheet_name='Weekly Schedule')
        worksheet = writer.sheets['Weekly Schedule']
        for idx, col in enumerate(schedule_df.columns):
            worksheet.column_dimensions[chr(66 + idx)].width = 30  # B, C, D, E, F columns
        worksheet.column_dimensions['A'].width = 15  # Time column
    
    print(f"Schedule saved as {filename}")

def analyze_schedule(schedule_df, data):
    """
    Perform basic analysis on the schedule
    """
    analysis = {
        'total_classes': schedule_df.notna().sum().sum(),
        'classes_per_day': schedule_df.notna().sum(),
        'free_slots_per_day': schedule_df.isna().sum(),
    }
    
    # Calculate average satisfaction per day
    satisfaction_by_day = {day: [] for day in schedule_df.columns}
    for student in data:
        for subject in student['Asignaturas']:
            day = subject['Dia']
            if day in satisfaction_by_day:
                satisfaction_by_day[day].append(subject['Satisfaccion'])
    
    analysis['avg_satisfaction_per_day'] = {
        day: np.mean(scores) if scores else 0 
        for day, scores in satisfaction_by_day.items()
    }
    
    return analysis



def read_json_schedule(filename='schedule.json'):
    """
    Reads schedule data from a JSON file
    """
    
    with open(filename, 'r', encoding="utf-8") as file:
        schedule_data = json.load(file)
        json_df = create_weekly_schedule_pandas(schedule_data)
        
        print("\nWeekly Schedule (JSON view):")
        print(json_df)
        
        save_styled_schedule(json_df, 'weekly_schedule_json.xlsx')
        
        analysis_results = analyze_schedule(json_df, schedule_data)
        
        print("\nSchedule Analysis (JSON view):")
        for metric, value in analysis_results.items():
            print(f"{metric}: {value}")

if __name__ == '__main__':
    read_json_schedule("Horarios_asignados.json")