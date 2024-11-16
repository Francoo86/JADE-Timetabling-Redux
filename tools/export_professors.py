import pandas as pd
import numpy as np
import json
import os

def create_professor_schedule(data, professor_name):
    """
    Creates a schedule DataFrame for a specific professor
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
    
    # Find the professor's data
    for person in data:
        if person['Nombre'] == professor_name:
            # Fill schedule for this professor
            for subject in person['Asignaturas']:
                time_slot = time_blocks[subject['Bloque']]
                day = subject['Dia']
                
                content = (f"Subject: {subject['Nombre']}\n"
                          f"Room: {subject['Sala']}\n"
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
            
        try:
            satisfaction = int(val.split('Satisfaction: ')[-1].split('/')[0])
            
            if satisfaction <= 5:
                # Red to Yellow
                green = int((satisfaction / 5) * 255)
                return f'background-color: #{255:02X}{green:02X}00'
            else:
                # Yellow to Green
                red = int(255 - ((satisfaction - 5) / 5) * 255)
                return f'background-color: #{red:02X}FF00'
        except:
            return 'background-color: #E6F3FF'
    
    return (df.style
            .map(satisfaction_color)
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

def export_all_professor_schedules(data, output_filename='professor_schedules.xlsx'):
    """
    Exports all professor schedules to a single Excel file with multiple worksheets
    """
    # Get unique professors
    professors = [person['Nombre'] for person in data]
    
    # Create Excel writer object
    with pd.ExcelWriter(output_filename, engine='openpyxl') as writer:
        for professor in professors:
            # Create and save schedule for each professor
            professor_df = create_professor_schedule(data, professor)
            styled_professor = style_dataframe(professor_df)
            
            # Clean sheet name (remove special characters that Excel doesn't allow)
            sheet_name = f"{professor[:30]}"  # Limit length to 30 characters
            sheet_name = "".join(c for c in sheet_name if c.isalnum() or c in (' ', '-', '_'))
            
            styled_professor.to_excel(writer, sheet_name=sheet_name, index=True)
            
            # Adjust column widths
            worksheet = writer.sheets[sheet_name]
            for idx in range(len(professor_df.columns) + 1):
                col_letter = chr(65 + idx)
                if idx == 0:
                    worksheet.column_dimensions[col_letter].width = 15  # Time column
                else:
                    worksheet.column_dimensions[col_letter].width = 30  # Day columns

    print(f"All professor schedules have been exported to {output_filename}")

def read_json_schedule(filename='schedule.json'):
    """
    Reads schedule data from a JSON file and exports all professor schedules
    """
    with open(filename, 'r', encoding='utf-8') as file:
        schedule_data = json.load(file)
        export_all_professor_schedules(schedule_data)
        print("\nProfessor schedule export completed successfully!")

if __name__ == '__main__':
    read_json_schedule("Horarios_asignados.json")