import json
import os
from collections import defaultdict

def load_json_file(filepath):
    """Load JSON file with proper encoding"""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            return json.load(f)
    except UnicodeDecodeError:
        with open(filepath, 'r', encoding='latin-1') as f:
            return json.load(f)

def find_matches(profesores, salas):
    """Find matches between professors' subjects and room assignments."""
    matches = []
    mismatches = []
    
    if isinstance(profesores, dict):
        profesores = [profesores]
    if isinstance(salas, dict):
        salas = [salas]
    
    for profesor in profesores:
        nombre_profesor = profesor.get("Nombre", "Unknown")
        asignaturas_profesor = profesor.get("Asignaturas", [])
        
        for asignatura_prof in asignaturas_profesor:
            nombre_asignatura_prof = asignatura_prof.get("Nombre", "")
            dia_prof = asignatura_prof.get("Dia", "")
            bloque_prof = asignatura_prof.get("Bloque", -1)
            sala_prof = asignatura_prof.get("Sala", "")
            
            found_match = False
            
            for sala in salas:
                codigo_sala = sala.get("Codigo", "")
                asignaturas_sala = sala.get("Asignaturas", [])
                
                if sala_prof == codigo_sala:
                    for asignatura_sala in asignaturas_sala:
                        nombre_asignatura_sala = asignatura_sala.get("Nombre", "")
                        dia_sala = asignatura_sala.get("Dia", "")
                        bloque_sala = asignatura_sala.get("Bloque", -1)
                        
                        if (nombre_asignatura_prof == nombre_asignatura_sala and 
                            dia_prof == dia_sala and 
                            bloque_prof == bloque_sala):
                            
                            matches.append({
                                "Profesor": nombre_profesor,
                                "Asignatura": nombre_asignatura_prof,
                                "Sala": sala_prof,
                                "Dia": dia_prof,
                                "Bloque": bloque_prof,
                                "Match": True
                            })
                            found_match = True
                            break
                
                if found_match:
                    break
            
            if not found_match:
                mismatches.append({
                    "Profesor": nombre_profesor,
                    "Asignatura": nombre_asignatura_prof,
                    "Sala_Asignada": sala_prof,
                    "Dia": dia_prof,
                    "Bloque": bloque_prof,
                    "Match": False,
                    "Reason": "No matching room-subject found"
                })
    
    return matches, mismatches

def validate_complete_assignment(input_profesores, output_profesores, output_salas):
    """Complete validation including completeness and conflicts"""
    report = {
        "total_requested_hours": 0,
        "total_assigned_hours": 0,
        "total_requested_subjects": 0,
        "total_completed_subjects": 0,
        "professors_analysis": {},
        "double_bookings": [],
        "unassigned_subjects": [],
        "room_conflicts": [],
        "professor_conflicts": []
    }
    
    # Create tracking structures
    professor_schedule = {}  # (prof_name, day, block) -> subject
    room_schedule = {}       # (room, day, block) -> (subject, professor)
    
    # 1. Build complete picture from output
    for output_prof in output_profesores:
        prof_name = output_prof["Nombre"]
        
        # Track assigned hours by subject instance
        subject_hours = defaultdict(lambda: {"assigned": 0, "blocks": []})
        
        for asig in output_prof.get("Asignaturas", []):
            subject_name = asig["Nombre"]
            day = asig["Dia"]
            block = asig["Bloque"]
            room = asig["Sala"]
            instance = asig.get("Instance", 0)
            
            # Create instance key
            instance_key = f"{subject_name}-{instance}"
            
            # Track hours
            subject_hours[instance_key]["assigned"] += 1
            subject_hours[instance_key]["blocks"].append({
                "day": day, "block": block, "room": room
            })
            
            # Check professor conflicts
            prof_key = (prof_name, day, block)
            if prof_key in professor_schedule:
                report["professor_conflicts"].append({
                    "professor": prof_name,
                    "day": day,
                    "block": block,
                    "subjects": [professor_schedule[prof_key], subject_name]
                })
            else:
                professor_schedule[prof_key] = subject_name
        
        report["professors_analysis"][prof_name] = subject_hours
    
    # 2. Check room conflicts from room perspective
    for sala in output_salas:
        room_code = sala["Codigo"]
        for asig in sala.get("Asignaturas", []):
            key = (room_code, asig["Dia"], asig["Bloque"])
            subject_prof = (asig["Nombre"], asig.get("Docente", "Unknown"))
            
            if key in room_schedule:
                existing = room_schedule[key]
                if existing != subject_prof:
                    report["double_bookings"].append({
                        "room": room_code,
                        "day": asig["Dia"],
                        "block": asig["Bloque"],
                        "conflict": [
                            {"subject": existing[0], "professor": existing[1]},
                            {"subject": subject_prof[0], "professor": subject_prof[1]}
                        ]
                    })
            else:
                room_schedule[key] = subject_prof
    
    # 3. Compare with input requirements
    for input_prof in input_profesores:
        prof_name = input_prof["Nombre"]
        prof_analysis = report["professors_analysis"].get(prof_name, {})
        
        # Track instances for subjects with same name
        subject_instance_counter = defaultdict(int)
        
        for input_subj in input_prof.get("Asignaturas", []):
            subj_name = input_subj["Nombre"]
            subj_code = input_subj.get("CodigoAsignatura", "")
            required_hours = input_subj["Horas"]
            paralelo = input_subj.get("Paralelo", "")
            
            # Create instance key
            instance = subject_instance_counter[subj_name]
            subject_instance_counter[subj_name] += 1
            instance_key = f"{subj_name}-{instance}"
            
            report["total_requested_hours"] += required_hours
            report["total_requested_subjects"] += 1
            
            # Get assigned hours for this instance
            assigned_info = prof_analysis.get(instance_key, {"assigned": 0, "blocks": []})
            assigned_hours = assigned_info["assigned"]
            report["total_assigned_hours"] += assigned_hours
            
            # Check if subject is complete
            if assigned_hours >= required_hours:
                report["total_completed_subjects"] += 1
            else:
                report["unassigned_subjects"].append({
                    "professor": prof_name,
                    "subject": subj_name,
                    "code": subj_code,
                    "paralelo": paralelo,
                    "instance": instance,
                    "required": required_hours,
                    "assigned": assigned_hours,
                    "missing": required_hours - assigned_hours,
                    "completion_rate": (assigned_hours / required_hours * 100) if required_hours > 0 else 0
                })
    
    # 4. Calculate metrics
    report["hour_completion_rate"] = (report["total_assigned_hours"] / 
                                     report["total_requested_hours"] * 100 
                                     if report["total_requested_hours"] > 0 else 0)
    
    report["subject_completion_rate"] = (report["total_completed_subjects"] / 
                                        report["total_requested_subjects"] * 100 
                                        if report["total_requested_subjects"] > 0 else 0)
    
    # 5. Room utilization
    total_room_slots = len(output_salas) * 5 * 9  # rooms * days * blocks
    used_slots = len(room_schedule)
    report["room_utilization"] = (used_slots / total_room_slots * 100) if total_room_slots > 0 else 0
    
    return report

def print_detailed_report(report, matches, mismatches):
    """Print comprehensive validation report"""
    print("\n" + "="*80)
    print("COMPLETE VALIDATION REPORT")
    print("="*80)
    
    # 1. Consistency Check
    print(f"\n1. CONSISTENCY CHECK:")
    print(f"   - Matches: {len(matches)}")
    print(f"   - Mismatches: {len(mismatches)}")
    print(f"   - Status: {'✓ PASS' if not mismatches else '✗ FAIL'}")
    
    # 2. Completion Metrics
    print(f"\n2. COMPLETION METRICS:")
    print(f"   - Hour Completion Rate: {report['hour_completion_rate']:.1f}%")
    print(f"   - Subject Completion Rate: {report['subject_completion_rate']:.1f}%")
    print(f"   - Total Hours Requested: {report['total_requested_hours']}")
    print(f"   - Total Hours Assigned: {report['total_assigned_hours']}")
    print(f"   - Subjects Requested: {report['total_requested_subjects']}")
    print(f"   - Subjects Completed: {report['total_completed_subjects']}")
    
    # 3. Resource Utilization
    print(f"\n3. RESOURCE UTILIZATION:")
    print(f"   - Room Utilization: {report['room_utilization']:.1f}%")
    
    # 4. Conflicts
    print(f"\n4. CONFLICTS:")
    print(f"   - Double Bookings (Rooms): {len(report['double_bookings'])}")
    print(f"   - Professor Conflicts: {len(report['professor_conflicts'])}")
    
    if report['double_bookings']:
        print("\n   Room Double Bookings:")
        for db in report['double_bookings'][:5]:
            print(f"   - {db['room']} on {db['day']} Block {db['block']}:")
            for conf in db['conflict']:
                print(f"     * {conf['subject']} ({conf['professor']})")
    
    # 5. Incomplete Assignments
    if report['unassigned_subjects']:
        print(f"\n5. INCOMPLETE ASSIGNMENTS: {len(report['unassigned_subjects'])}")
        
        # Group by professor
        by_professor = defaultdict(list)
        for item in report['unassigned_subjects']:
            by_professor[item['professor']].append(item)
        
        for prof, subjects in list(by_professor.items())[:5]:
            print(f"\n   Professor: {prof}")
            for subj in subjects:
                print(f"   - {subj['subject']} [{subj['code']}]:")
                print(f"     * Required: {subj['required']} hours")
                print(f"     * Assigned: {subj['assigned']} hours")
                print(f"     * Completion: {subj['completion_rate']:.1f}%")
    
    # 6. Summary
    print(f"\n6. OVERALL STATUS:")
    if report['hour_completion_rate'] == 100 and not mismatches and not report['double_bookings']:
        print("   ✓ PERFECT: All assignments completed without conflicts!")
    elif report['hour_completion_rate'] >= 90:
        print("   ✓ GOOD: High completion rate with minor issues")
    elif report['hour_completion_rate'] >= 70:
        print("   ⚠ FAIR: Acceptable completion rate but needs improvement")
    else:
        print("   ✗ POOR: Low completion rate, significant issues")
    
    print("\n" + "="*80)

def validate_scenarios(scenario="small"):
    """Main validation function"""
    CURRENT_FILE = os.path.dirname(os.path.abspath(__file__))
    
    try:
        # Load input professors (original requirements)
        input_prof_path = os.path.join(CURRENT_FILE, "agent_input", "scenarios", scenario, "profesores.json")
        input_profesores = load_json_file(input_prof_path)
        
        # Load output files
        output_prof_path = os.path.join(CURRENT_FILE, "agent_output", scenario, "Horarios_asignados.json")
        output_profesores = load_json_file(output_prof_path)
        
        output_sala_path = os.path.join(CURRENT_FILE, "agent_output", scenario, "Horarios_salas.json")
        output_salas = load_json_file(output_sala_path)
        
        # Run validations
        matches, mismatches = find_matches(output_profesores, output_salas)
        report = validate_complete_assignment(input_profesores, output_profesores, output_salas)
        
        print("=== MISMATCHES ===")
        if mismatches:
            for mismatch in mismatches:
                print(f"Mismatch: {mismatch['Profesor']} - {mismatch['Asignatura']} "
                      f"({mismatch['Sala_Asignada']}, {mismatch['Dia']}, {mismatch['Bloque']})")
        else:
            print("No mismatches found.")
        
        # Print comprehensive report
        print_detailed_report(report, matches, mismatches)
        
        # Return success status
        return (len(mismatches) == 0 and 
                report['hour_completion_rate'] == 100 and 
                len(report['double_bookings']) == 0)
        
    except Exception as e:
        print(f"Error during validation: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Complete validation of professor and room assignments.")
    parser.add_argument("--scenario", type=str, default="small", 
                       help="Scenario to validate (default: small), choices: small, medium, full.")
    args = parser.parse_args()
    
    success = validate_scenarios(args.scenario)
    exit(0 if success else 1)