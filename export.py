import pandas as pd
import numpy as np

def create_schedule_pandas(data, day='Lunes'):
    """
    Creates a schedule using pandas DataFrame
    """
    # Define time blocks
    time_blocks = {
        1: "08:00 - 09:00",
        2: "09:15 - 10:15",
        3: "10:30 - 11:30",
        4: "11:45 - 12:45",
        5: "12:50 - 13:50",
        5: "13:55 - 14:55",
        6: "15:00 - 16:00",
        7: "16:15 - 17:15",
        8: "17:30 - 18:30",
    }
    
    # Create empty DataFrame
    schedule_df = pd.DataFrame(
        index=time_blocks.values(),
        columns=['Room A-1', 'Room B-2']
    )
    
    # Fill schedule
    for student in data:
        for subject in student['Asignaturas']:
            if subject['Dia'] == day:
                time_slot = time_blocks[subject['Bloque']]
                room = f"Room {subject['Sala']}"
                
                # Create formatted cell content
                content = (f"{subject['Nombre']}\n"
                          f"Student: {student['Nombre']}\n"
                          f"Satisfaction: {subject['Satisfaccion']}/10")
                
                schedule_df.at[time_slot, room] = content
    
    # Fill NaN with empty string
    schedule_df = schedule_df.fillna('')
    
    return schedule_df

def save_styled_schedule(schedule_df, filename='schedule.xlsx'):
    """
    Saves the schedule DataFrame to Excel with styling
    """
    # Create a Styler object
    styler = schedule_df.style
    
    # Add background color to non-empty cells
    def background_color(val):
        if pd.isna(val) or val == '':
            return ''
        return 'background-color: #e6f3ff'
    
    # Apply styles
    styler = (styler.applymap(background_color)
                    .set_properties(**{
                        'border': '1px solid black',
                        'padding': '10px',
                        'text-align': 'center',
                        'white-space': 'pre-wrap'
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
    
    # Save to Excel
    styler.to_excel(filename, engine='openpyxl', index=True)
    print(f"Schedule saved as {filename}")

# Example usage
schedule_data = [
    {
        "Nombre": "María Gonzalez",
        "AsignaturasCompletadas": 3,
        "Solicitudes": 3,
        "Asignaturas": [{
            "Nombre": "Programación en Java",
            "Sala": "A-1",
            "Bloque": 3,
            "Dia": "Lunes",
            "Satisfaccion": 5
        }, {
            "Nombre": "Programación en Python",
            "Sala": "B-2",
            "Bloque": 1,
            "Dia": "Lunes",
            "Satisfaccion": 10
        }]
    }
    # ... add more data as needed
]

# Create and display schedule
schedule_df = create_schedule_pandas(schedule_data)

# Print to console (for quick view)
print("\nSchedule (console view):")
print(schedule_df)

# Save as styled Excel file
save_styled_schedule(schedule_df)

# If you want to do some analysis:
def analyze_schedule(schedule_df, data):
    """
    Perform basic analysis on the schedule
    """
    analysis = {
        'total_classes': schedule_df.notna().sum().sum(),
        'classes_per_room': schedule_df.notna().sum(),
        'free_slots': schedule_df.isna().sum(),
    }
    
    # Calculate average satisfaction per room
    satisfaction_by_room = {'Room A-1': [], 'Room B-2': []}
    for student in data:
        for subject in student['Asignaturas']:
            room = f"Room {subject['Sala']}"
            satisfaction_by_room[room].append(subject['Satisfaccion'])
    
    analysis['avg_satisfaction'] = {
        room: np.mean(scores) if scores else 0 
        for room, scores in satisfaction_by_room.items()
    }
    
    return analysis

# Run analysis
analysis_results = analyze_schedule(schedule_df, schedule_data)
print("\nSchedule Analysis:")
for metric, value in analysis_results.items():
    print(f"{metric}: {value}")