import json
from collections import defaultdict
from typing import Dict, List, Tuple
import statistics

def load_json_data(json_str: str) -> dict:
    return json.loads(json_str)

def analyze_schedule_match(input_data: dict, output_data: dict) -> Tuple[dict, dict]:
    """Analyzes how well the output schedule matches the input requirements"""
    stats = {}
    details = defaultdict(dict)
    
    # Get input requirements
    input_subjects = {}
    for subject in input_data["Asignaturas"]:
        key = (subject["Nombre"], subject["CodigoAsignatura"])
        input_subjects[key] = {
            "required": subject["Horas"],
            "nivel": subject["Nivel"]
        }

    # Analyze output assignments
    output_subjects = defaultdict(int)
    block_distribution = defaultdict(lambda: defaultdict(int))
    room_usage = defaultdict(int)
    consecutive_blocks = 0
    total_blocks = 0
    
    for assignment in output_data["Asignaturas"]:
        key = (assignment["Nombre"], assignment["CodigoAsignatura"])
        output_subjects[key] += 1
        block_distribution[assignment["Dia"]][key] += 1
        room_usage[assignment["Sala"]] += 1
        total_blocks += 1

    # Count consecutive blocks
    sorted_assignments = sorted(output_data["Asignaturas"], 
                              key=lambda x: (x["Dia"], x["Bloque"]))
    for i in range(1, len(sorted_assignments)):
        prev = sorted_assignments[i-1]
        curr = sorted_assignments[i]
        if (prev["Dia"] == curr["Dia"] and 
            prev["Nombre"] == curr["Nombre"] and
            curr["Bloque"] - prev["Bloque"] == 1):
            consecutive_blocks += 1

    # Calculate match percentages for each subject
    match_percentages = {}
    total_match = 0
    subject_count = 0
    
    for key, req in input_subjects.items():
        assigned = output_subjects[key]
        required = req["required"]
        # Fix division by zero
        if required == 0:
            match_percentage = 0
        else:
            match_percentage = min(100.0, (assigned / required) * 100)
            
        match_percentages[key[0]] = match_percentage
        total_match += match_percentage
        subject_count += 1
        
        details[key[0]] = {
            "required_hours": required,
            "assigned_hours": assigned,
            "match_percentage": match_percentage,
            "nivel": req["nivel"]
        }

    # Calculate overall statistics
    stats["overall_match"] = total_match / subject_count if subject_count > 0 else 0
    stats["total_subjects"] = subject_count
    stats["total_blocks_assigned"] = total_blocks
    stats["consecutive_block_percentage"] = (consecutive_blocks / total_blocks * 100 
                                           if total_blocks > 0 else 0)
    
    # Calculate error margin
    individual_errors = [abs(100 - match_percentages[subject]) 
                        for subject in match_percentages]
    stats["error_margin"] = statistics.mean(individual_errors) if individual_errors else 0
    stats["error_std_dev"] = statistics.stdev(individual_errors) if len(individual_errors) > 1 else 0

    return stats, dict(details)

def export_to_excel(all_stats: List[dict], filename: str = "schedule_analysis.xlsx"):
    """Exports analysis results to Excel"""
    import pandas as pd
    
    # Create DataFrames for different aspects of the analysis
    professor_stats = []
    subject_details = []
    
    for prof_data in all_stats:
        prof_name = prof_data["professor"]
        stats = prof_data["stats"]
        details = prof_data["details"]
        
        # Professor level statistics
        professor_stats.append({
            "Professor": prof_name,
            "Overall Match (%)": stats["overall_match"],
            "Total Subjects": stats["total_subjects"],
            "Total Blocks": stats["total_blocks_assigned"],
            "Consecutive Block (%)": stats["consecutive_block_percentage"],
            "Error Margin": stats["error_margin"],
            "Error Std Dev": stats["error_std_dev"]
        })
        
        # Subject level details
        for subject, detail in details.items():
            subject_details.append({
                "Professor": prof_name,
                "Subject": subject,
                "Required Hours": detail["required_hours"],
                "Assigned Hours": detail["assigned_hours"],
                "Match (%)": detail["match_percentage"],
                "Level": detail["nivel"]
            })
    
    # Create Excel writer object
    with pd.ExcelWriter(filename, engine='xlsxwriter') as writer:
        # Convert to DataFrames
        prof_df = pd.DataFrame(professor_stats)
        subj_df = pd.DataFrame(subject_details)
        
        # Write each DataFrame to a different sheet
        prof_df.to_excel(writer, sheet_name='Professor Stats', index=False)
        subj_df.to_excel(writer, sheet_name='Subject Details', index=False)
        
        # Add summary statistics
        summary_data = {
            "Metric": [
                "Average Overall Match (%)",
                "Total Professors",
                "Total Subjects",
                "Average Error Margin",
                "Global Error Std Dev"
            ],
            "Value": [
                prof_df["Overall Match (%)"].mean(),
                len(prof_df),
                subj_df["Subject"].nunique(),
                prof_df["Error Margin"].mean(),
                prof_df["Error Margin"].std()
            ]
        }
        summary_df = pd.DataFrame(summary_data)
        summary_df.to_excel(writer, sheet_name='Summary', index=False)

def print_analysis(prof_name: str, stats: dict, details: dict):
    """Prints formatted analysis results"""
    print(f"\n{'='*80}")
    print(f"Analysis for Professor: {prof_name}")
    print(f"{'='*80}")
    
    print("\nSubject Details:")
    print(f"{'Subject':<30} {'Required':<10} {'Assigned':<10} {'Match %':<10} {'Level':<6}")
    print("-" * 70)
    for subject, data in details.items():
        print(f"{subject[:30]:<30} {data['required_hours']:<10} "
              f"{data['assigned_hours']:<10} {data['match_percentage']:.1f}%    "
              f"{data['nivel']}")

    print("\nOverall Statistics:")
    print(f"Overall Schedule Match: {stats['overall_match']:.1f}%")
    print(f"Total Subjects: {stats['total_subjects']}")
    print(f"Total Blocks Assigned: {stats['total_blocks_assigned']}")
    print(f"Consecutive Block Usage: {stats['consecutive_block_percentage']:.1f}%")
    print(f"Error Margin: ±{stats['error_margin']:.1f}%")
    print(f"Error Standard Deviation: {stats['error_std_dev']:.1f}%")

def main():
    import os
    
    FILE_PATH = os.path.dirname(os.path.abspath(__file__))
    INPUT_PATH = os.path.abspath(os.path.join(FILE_PATH, os.pardir, "agent_input"))
    HORARIOS_ASIGNADOS = os.path.abspath(os.path.join(FILE_PATH, "Horarios_asignados.json"))
    
    INPUT_PROFS = os.path.join(INPUT_PATH, "inputOfProfesores.json")
    
    # Load both JSON files
    input_data = load_json_data(open(INPUT_PROFS, 'r', encoding="utf-8").read())
    output_data = load_json_data(open(HORARIOS_ASIGNADOS, 'r', encoding="utf-8").read())
    
    all_stats = []
    
    # Process each professor in the output data
    for output_prof in output_data:
        # Find matching professor in input data by name
        input_prof = next(
            (prof for prof in input_data if prof["Nombre"] == output_prof["Nombre"]), 
            None
        )
        
        if input_prof:
            print(f"\nProcessing professor: {input_prof['Nombre']}")
            stats, details = analyze_schedule_match(input_prof, output_prof)
            print_analysis(output_prof["Nombre"], stats, details)
            
            # Store results for Excel export
            all_stats.append({
                "professor": output_prof["Nombre"],
                "stats": stats,
                "details": details
            })
        else:
            print(f"\nWarning: No input data found for professor {output_prof['Nombre']}")
    
    # Export results to Excel
    export_to_excel(all_stats)
    print("\nResults have been exported to 'schedule_analysis.xlsx'")

if __name__ == "__main__":
    main()