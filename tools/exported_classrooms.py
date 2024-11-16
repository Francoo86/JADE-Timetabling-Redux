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
    return schedule_df.fillna('')

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

def style_dataframe(df):
    """
    Apply styling to DataFrame with satisfaction-based cell colors using Excel-compatible formats
    """
    def satisfaction_color(val):
        if pd.isna(val) or val == '':
            return ''
            
        # Extract satisfaction value from the cell content
        try:
            satisfaction = int(val.split('Satisfaction: ')[-1].split('/')[0])
            
            # Create color gradient from red (FF0000) to yellow (FFFF00) to green (00FF00)
            if satisfaction <= 5:
                # Red to Yellow
                green = int((satisfaction / 5) * 255)
                return f'background-color: #{255:02X}{green:02X}00'
            else:
                # Yellow to Green
                red = int(255 - ((satisfaction - 5) / 5) * 255)
                return f'background-color: #{red:02X}FF00'
        except:
            return 'background-color: #E6F3FF'  # Default blue for parsing errors
    
    return (df.style
            .map(satisfaction_color)  # Using .map instead of deprecated .applymap
            .set_properties(**{
                'border': '1px solid black',
                'padding': '10px',
                'text-align': 'center',
                'white-space': 'pre-wrap',
                'height': '100px'
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

def export_all_schedules(data, output_filename='all_schedules.xlsx'):
    """
    Exports all schedules (complete and per classroom) to a single Excel file
    with multiple worksheets
    """
    # Create Excel writer object
    with pd.ExcelWriter(output_filename, engine='openpyxl') as writer:
        # First, create and save the complete schedule
        complete_schedule_df = create_weekly_schedule_pandas(data)
        styled_complete = style_dataframe(complete_schedule_df)
        styled_complete.to_excel(writer, sheet_name='Complete Schedule', index=True)
        
        # Get unique classrooms
        classrooms = set()
        for student in data:
            for subject in student['Asignaturas']:
                classrooms.add(subject['Sala'])
        
        # Create and save schedule for each classroom as separate worksheets
        for classroom in classrooms:
            classroom_df = create_classroom_schedule(data, classroom)
            styled_classroom = style_dataframe(classroom_df)
            styled_classroom.to_excel(writer, sheet_name=f'Room {classroom}', index=True)
        
        # Adjust column widths for all worksheets
        for worksheet in writer.sheets.values():
            for idx in range(len(complete_schedule_df.columns) + 1):
                col_letter = chr(65 + idx)  # A, B, C, etc.
                if idx == 0:
                    worksheet.column_dimensions[col_letter].width = 15  # Time column
                else:
                    worksheet.column_dimensions[col_letter].width = 30  # Day columns

    print(f"All schedules have been exported to {output_filename}")

def read_json_schedule(filename='schedule.json'):
    """
    Reads schedule data from a JSON file and exports all schedules to a single Excel file
    """
    with open(filename, 'r', encoding="utf-8") as file:
        schedule_data = json.load(file)
        export_all_schedules(schedule_data)
        print("\nSchedule export completed successfully!")

if __name__ == '__main__':
    read_json_schedule("Horarios_asignados.json")