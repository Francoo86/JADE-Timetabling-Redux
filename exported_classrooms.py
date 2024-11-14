import pandas as pd
import numpy as np
import json
import os

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
    
    # Add background color to non-empty cells
    def background_color(val):
        if pd.isna(val) or val == '':
            return ''
        return 'background-color: #e6f3ff'  # Light blue for all rooms
    
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

def create_classroom_schedule(data, classroom):
    """
    Creates a schedule DataFrame for a specific classroom
    """
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
    
    days = ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes']
    
    schedule_df = pd.DataFrame(
        index=time_blocks.values(),
        columns=days,
    )
    
    # Fill schedule only for the specified classroom
    for student in data:
        for subject in student['Asignaturas']:
            if subject['Sala'] == classroom:
                time_slot = time_blocks[subject['Bloque']]
                day = subject['Dia']
                
                content = (f"{subject['Nombre']}\n"
                          f"Student: {student['Nombre']}\n"
                          f"Satisfaction: {subject['Satisfaccion']}/10")
                
                schedule_df.at[time_slot, day] = content
    
    return schedule_df.fillna('')

def export_classroom_schedules(data):
    """
    Exports separate Excel files for each classroom's schedule
    """
    # Create export directory if it doesn't exist
    export_dir = 'exported_classroom_data'
    if not os.path.exists(export_dir):
        os.makedirs(export_dir)
    
    # Get unique classrooms
    classrooms = set()
    for student in data:
        for subject in student['Asignaturas']:
            classrooms.add(subject['Sala'])
    
    # Create and save schedule for each classroom
    for classroom in classrooms:
        classroom_df = create_classroom_schedule(data, classroom)
        filename = os.path.join(export_dir, f'schedule_{classroom}.xlsx')
        save_styled_schedule(classroom_df, filename)
        print(f"Schedule for classroom {classroom} saved as {filename}")

def read_json_schedule(filename='schedule.json'):
    """
    Reads schedule data from a JSON file and exports classroom-specific schedules
    """
    with open(filename, 'r', encoding="utf-8") as file:
        schedule_data = json.load(file)
        
        # Export individual classroom schedules
        export_classroom_schedules(schedule_data)
        
        # Create and save the complete schedule as before
        complete_schedule_df = create_weekly_schedule_pandas(schedule_data)
        save_styled_schedule(complete_schedule_df, 'weekly_schedule_complete.xlsx')
        
        print("\nAll schedules have been exported successfully!")

if __name__ == '__main__':
    read_json_schedule("Horarios_asignados.json")