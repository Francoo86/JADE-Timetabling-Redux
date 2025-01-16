import pandas as pd
from collections import defaultdict
from typing import List, Dict

def analyze_schedules(prof_data_list: List[Dict], assigned_data_list: List[Dict]):
    """
    Analyze schedule for multiple professors.
    
    Args:
        prof_data_list (List[Dict]): List of professors' course load data
        assigned_data_list (List[Dict]): List of actual assigned schedule data
    
    Returns:
        pd.DataFrame: Analysis results for all professors
    """
    # Initialize analysis dictionary
    analysis = {
        'Professor': [],
        'RUT': [],
        'Subject_Code': [],
        'Subject_Name': [],
        'Assigned_Blocks': [],
        'Expected_Hours': [],
        'Blocks_Assigned': [],
        'Time_Conflicts': [],
        'Hours_Overassigned': []
    }
    
    # Create professor data mapping
    prof_data_map = {prof['RUT']: prof for prof in prof_data_list}
    
    # Process each professor's assignments
    for assigned_data in assigned_data_list:
        prof_name = assigned_data['Nombre']
        
        # Find matching professor data
        prof_data = None
        for p in prof_data_list:
            if p['Nombre'].strip() == prof_name.strip():
                prof_data = p
                break
        
        if not prof_data:
            print(f"Warning: No matching professor data found for {prof_name}")
            continue
        
        # Create expected hours mapping for this professor
        expected_hours = defaultdict(int)
        for subject in prof_data['Asignaturas']:
            expected_hours[subject['CodigoAsignatura']] += subject['Horas']
        
        # Create assigned blocks mapping and check for conflicts
        assigned_blocks = defaultdict(list)
        time_slots = defaultdict(list)
        
        for assignment in assigned_data['Asignaturas']:
            code = assignment['CodigoAsignatura']
            time_key = f"{assignment['Dia']}-{assignment['Bloque']}"
            assigned_blocks[code].append(time_key)
            time_slots[time_key].append(code)
        
        # Analyze each subject for this professor
        processed_subjects = set()
        
        # Combine all subject codes from both expected and assigned
        all_subject_codes = set(expected_hours.keys()) | set(assigned_blocks.keys())
        
        for subject_code in all_subject_codes:
            if subject_code in processed_subjects:
                continue
                
            processed_subjects.add(subject_code)
            
            # Get subject name from either source
            subject_name = next(
                (s['Nombre'] for s in prof_data['Asignaturas'] 
                 if s['CodigoAsignatura'] == subject_code),
                next(
                    (s['Nombre'] for s in assigned_data['Asignaturas'] 
                     if s['CodigoAsignatura'] == subject_code),
                    'Unknown'
                )
            )
            
            # Count assigned blocks
            blocks = len(assigned_blocks[subject_code])
            
            # Check for time conflicts
            has_conflicts = any(
                len(slots) > 1 for slots in time_slots.values()
                if subject_code in slots
            )
            
            # Compare assigned vs expected hours
            expected = expected_hours[subject_code]
            hours_overassigned = blocks - expected if blocks > expected else 0
            
            # Add to analysis
            analysis['Professor'].append(prof_name)
            analysis['RUT'].append(prof_data['RUT'])
            analysis['Subject_Code'].append(subject_code)
            analysis['Subject_Name'].append(subject_name)
            analysis['Assigned_Blocks'].append(blocks)
            analysis['Expected_Hours'].append(expected)
            analysis['Blocks_Assigned'].append(','.join(assigned_blocks[subject_code]))
            analysis['Time_Conflicts'].append('Yes' if has_conflicts else 'No')
            analysis['Hours_Overassigned'].append(hours_overassigned)
    
    # Create DataFrame
    df = pd.DataFrame(analysis)
    
    # Sort by professor name and subject code
    df = df.sort_values(['Professor', 'Subject_Code'])
    
    return df

def save_to_excel(df, output_path='schedule_analysis.xlsx'):
    """
    Save analysis to Excel with formatting.
    
    Args:
        df (pd.DataFrame): Analysis results
        output_path (str): Path to save Excel file
    """
    with pd.ExcelWriter(output_path, engine='openpyxl') as writer:
        df.to_excel(writer, index=False, sheet_name='Schedule Analysis')
        
        # Get workbook and worksheet
        workbook = writer.book
        worksheet = writer.sheets['Schedule Analysis']
        
        # Auto-adjust column widths
        for column in worksheet.columns:
            max_length = 0
            column = [cell for cell in column]
            for cell in column:
                try:
                    if len(str(cell.value)) > max_length:
                        max_length = len(cell.value)
                except:
                    pass
            adjusted_width = (max_length + 2)
            worksheet.column_dimensions[column[0].column_letter].width = adjusted_width

# Example usage:
if __name__ == "__main__":
    import json
    import os
    
    FILE_PATH = os.path.dirname(os.path.abspath(__file__))
    INPUT_PATH = os.path.abspath(os.path.join(FILE_PATH, os.pardir, "agent_input"))
    HORARIOS_ASIGNADOS = os.path.abspath(os.path.join(FILE_PATH, "Horarios_asignados.json"))
    PROFESORES = os.path.abspath(os.path.join(os.pardir, INPUT_PATH, "inputOfProfesores.json"))
    
    # Sample input data for multiple professors
    prof_data_list = json.load(open(PROFESORES, 'r', encoding="utf-8"))
    
    assigned_data_list = json.load(open(HORARIOS_ASIGNADOS, 'r', encoding="utf-8"))
    # Run analysis for all professors
    analysis_df = analyze_schedules(prof_data_list, assigned_data_list)
    
    # Save to Excel
    save_to_excel(analysis_df, 'schedule_analysis_validator.xlsx')