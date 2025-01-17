import pandas as pd
from collections import defaultdict
from typing import List, Dict, Set, Tuple
import json
from dataclasses import dataclass
from enum import Enum

class ContractType(Enum):
    FULL_TIME = "JORNADA_COMPLETA"
    HALF_TIME = "MEDIA_JORNADA" 
    PART_TIME = "JORNADA_PARCIAL"

@dataclass
class ConstraintViolation:
    constraint_id: int
    description: str
    severity: str  # 'HIGH', 'MEDIUM', 'LOW'

class ScheduleValidator:
    """Enhanced constraint validation system"""
    
    @staticmethod
    def get_contract_type(total_hours: int) -> ContractType:
        if total_hours >= 16:
            return ContractType.FULL_TIME
        elif total_hours >= 12:
            return ContractType.HALF_TIME
        return ContractType.PART_TIME

    @staticmethod
    def is_workshop_or_lab(subject_name: str) -> bool:
        return any(keyword in subject_name.upper() 
                  for keyword in ['TAL', 'LAB'])

    @staticmethod
    def get_campus_from_room(room_code: str) -> str:
        if room_code.startswith('KAU'):
            return 'Kaufmann'
        elif room_code.startswith('HUA'):
            return 'Huayquique'
        return 'Playa Brava'

    def validate_assignment(self, assignment: Dict, subject_info: Dict, 
                          room_info: Dict, day_schedule: List[Dict]) -> List[ConstraintViolation]:
        violations = []
        
        # Constraint 1: Time blocks (8:00-18:30)
        if not (1 <= assignment['Bloque'] <= 9):
            violations.append(ConstraintViolation(
                1,
                f"Invalid time block {assignment['Bloque']} (must be 1-9)",
                'HIGH'
            ))

        # Constraint 2: Continuous blocks limit
        day_blocks = [a['Bloque'] for a in day_schedule 
                     if a['Nombre'] == assignment['Nombre']]
        if not self.validate_continuous_blocks(
            day_blocks, 
            self.is_workshop_or_lab(subject_info['Actividad'])
        ):
            violations.append(ConstraintViolation(
                2,
                "More than 2 continuous hours for non-workshop class",
                'MEDIUM'
            ))

        # Constraint 3: First year morning preference
        if subject_info['Nivel'] <= 2 and assignment['Bloque'] > 4:
            violations.append(ConstraintViolation(
                3,
                "First year class scheduled in afternoon",
                'LOW'
            ))

        # Constraint 4: Campus transitions
        if not self.validate_campus_transitions(day_schedule):
            violations.append(ConstraintViolation(
                4,
                "Invalid campus transition pattern",
                'HIGH'
            ))

        # Constraint 6: Continuous gaps for full/half-time
        contract_type = self.get_contract_type(
            sum(s['Horas'] for s in subject_info.get('Asignaturas', []))
        )
        if contract_type in [ContractType.FULL_TIME, ContractType.HALF_TIME]:
            if not self.validate_gaps(day_blocks):
                violations.append(ConstraintViolation(
                    6,
                    "Too many gaps in schedule for full/half-time professor",
                    'MEDIUM'
                ))

        # Constraint 7: Year-based scheduling
        if not self.validate_year_schedule(subject_info['Nivel'], assignment['Bloque']):
            violations.append(ConstraintViolation(
                7,
                f"Inappropriate time slot for year {subject_info['Nivel']}",
                'MEDIUM'
            ))

        # Constraint 8: Class size and room capacity
        if not self.validate_class_size(subject_info['Vacantes'], room_info['Capacidad']):
            violations.append(ConstraintViolation(
                8,
                f"Invalid class size ({subject_info['Vacantes']}) for room capacity ({room_info['Capacidad']})",
                'HIGH'
            ))

        return violations

    def validate_continuous_blocks(self, blocks: List[int], is_workshop: bool) -> bool:
        if is_workshop:
            return True
            
        blocks = sorted(blocks)
        continuous = 1
        
        for i in range(1, len(blocks)):
            if blocks[i] == blocks[i-1] + 1:
                continuous += 1
                if continuous > 2:
                    return False
            else:
                continuous = 1
        return True

    def validate_campus_transitions(self, day_schedule: List[Dict]) -> bool:
        transitions = 0
        prev_campus = None
        prev_block = None
        
        schedule = sorted(day_schedule, key=lambda x: x['Bloque'])
        
        for assignment in schedule:
            campus = self.get_campus_from_room(assignment['Sala'])
            
            if prev_campus and campus != prev_campus:
                transitions += 1
                if transitions > 1:
                    return False
                if prev_block and assignment['Bloque'] - prev_block == 1:
                    return False
            
            prev_campus = campus
            prev_block = assignment['Bloque']
        
        return True

    def validate_gaps(self, blocks: List[int]) -> bool:
        blocks = sorted(blocks)
        for i in range(1, len(blocks)):
            if blocks[i] - blocks[i-1] > 2:
                return False
        return True

    def validate_year_schedule(self, nivel: int, block: int) -> bool:
        is_morning = block <= 4
        is_odd_year = nivel % 2 == 1
        return (is_odd_year and is_morning) or (not is_odd_year and not is_morning)

    def validate_class_size(self, students: int, room_capacity: int) -> bool:
        if students < 9 or students > 70:
            return False
        return students <= room_capacity

class ScheduleAnalyzer:
    def __init__(self, professors_data: List[Dict], rooms_data: List[Dict]):
        self.professors_data = professors_data
        self.rooms_data = {room['Codigo']: room for room in rooms_data}
        self.validator = ScheduleValidator()

    def analyze_schedule(self, assignments_data: List[Dict]) -> pd.DataFrame:
        """Analyze schedule assignments and generate detailed report"""
        
        analysis_data = []
        
        for prof_assignments in assignments_data:
            prof_name = prof_assignments['Nombre']
            prof_data = next(
                (p for p in self.professors_data if p['Nombre'].strip() == prof_name.strip()),
                None
            )
            
            if not prof_data:
                print(f"Warning: No matching professor data for {prof_name}")
                continue

            self._analyze_professor_schedule(
                prof_data, 
                prof_assignments, 
                analysis_data
            )

        return pd.DataFrame(analysis_data)

    def _analyze_professor_schedule(self, prof_data: Dict, assignments: Dict, 
                                  analysis_data: List[Dict]):
        """Analyze schedule for a single professor"""
        
        # Track assignments by subject
        subject_assignments = defaultdict(list)
        for assignment in assignments['Asignaturas']:
            subject_assignments[assignment['CodigoAsignatura']].append(assignment)

        # Analyze each subject
        for subject in prof_data['Asignaturas']:
            code = subject['CodigoAsignatura']
            assigned = subject_assignments.get(code, [])
            
            # Get violations for each assignment
            violations = []
            for assignment in assigned:
                room_info = self.rooms_data.get(assignment['Sala'], {})
                day_schedule = [
                    a for a in assigned 
                    if a['Dia'] == assignment['Dia']
                ]
                
                assignment_violations = self.validator.validate_assignment(
                    assignment,
                    subject,
                    room_info,
                    day_schedule
                )
                violations.extend(assignment_violations)

            # Calculate metrics
            assigned_hours = len(assigned)
            expected_hours = subject['Horas']
            completion_rate = (assigned_hours / expected_hours) * 100 if expected_hours > 0 else 0
            
            # Group violations by severity
            high_violations = sum(1 for v in violations if v.severity == 'HIGH')
            medium_violations = sum(1 for v in violations if v.severity == 'MEDIUM')
            low_violations = sum(1 for v in violations if v.severity == 'LOW')
            
            # Calculate satisfaction score (0-100)
            base_score = 100
            base_score -= high_violations * 20  # -20 points per high severity violation
            base_score -= medium_violations * 10  # -10 points per medium severity violation
            base_score -= low_violations * 5  # -5 points per low severity violation
            satisfaction_score = max(0, min(100, base_score))

            analysis_data.append({
                'Professor': prof_data['Nombre'],
                'RUT': prof_data['RUT'],
                'Subject_Code': code,
                'Subject_Name': subject['Nombre'],
                'Expected_Hours': expected_hours,
                'Assigned_Hours': assigned_hours,
                'Completion_Rate': round(completion_rate, 2),
                'High_Violations': high_violations,
                'Medium_Violations': medium_violations,
                'Low_Violations': low_violations,
                'Satisfaction_Score': satisfaction_score,
                'Violation_Details': '; '.join(str(v.description) for v in violations) if violations else 'None'
            })

def main():
    import os
    
    # File paths
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    FULL_PATH = os.path.abspath(os.path.join(BASE_DIR, ".."))
    INPUT_DIR = os.path.join(FULL_PATH, "agent_input")
    OUTPUT_DIR = os.path.join(FULL_PATH, "agent_output")
    
    # Ensure output directory exists
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    # Load input data
    with open(os.path.join(INPUT_DIR, "inputOfProfesores.json"), 'r', encoding='utf-8') as f:
        professors_data = json.load(f)
    
    with open(os.path.join(INPUT_DIR, "inputOfSala.json"), 'r', encoding='utf-8') as f:
        rooms_data = json.load(f)
    
    with open(os.path.join(OUTPUT_DIR, "Horarios_asignados.json"), 'r', encoding='utf-8') as f:
        assignments_data = json.load(f)
    
    # Create analyzer and generate report
    analyzer = ScheduleAnalyzer(professors_data, rooms_data)
    analysis_df = analyzer.analyze_schedule(assignments_data)
    
    print("DIR", OUTPUT_DIR)
    
    # make an excel of this
    analysis_df.to_excel(os.path.join(OUTPUT_DIR, "schedule_analysis_ultimate.xlsx"), index=False)
    
    print("Analysis completed. Results saved to 'schedule_analysis_ultimate.xlsx'")
    
if __name__ == "__main__":
    main()