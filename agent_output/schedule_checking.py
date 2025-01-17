import pandas as pd
from collections import defaultdict
from typing import List, Dict, Set, Tuple, Optional
import json
from dataclasses import dataclass
from enum import Enum
import os

class ActivityType(Enum):
    THEORY = "TEORIA"
    LAB = "LABORATORIO"
    PRACTICE = "PRACTICA"
    WORKSHOP = "TALLER"
    TUTORING = "TUTORIA"
    ASSISTANCE = "AYUDANTIA"

    @classmethod
    def from_abbreviation(cls, abbrev: str) -> Optional['ActivityType']:
        """Convert abbreviated activity type to full ActivityType"""
        mapping = {
            'TEO': cls.THEORY,
            'LAB': cls.LAB,
            'PRA': cls.PRACTICE,
            'TAL': cls.WORKSHOP,
            'TUT': cls.TUTORING,
            'AYU': cls.ASSISTANCE
        }
        return mapping.get(abbrev.upper())

    @classmethod
    def normalize_activity(cls, activity: str) -> str:
        """Normalize activity string to standard format"""
        # First try direct match
        try:
            return cls(activity).value
        except ValueError:
            # Try to match abbreviation
            activity_type = cls.from_abbreviation(activity)
            if activity_type:
                return activity_type.value
            # Default to THEORY if unknown
            print(f"Warning: Unknown activity type '{activity}', defaulting to THEORY")
            return cls.THEORY.value

class ContractType(Enum):
    FULL_TIME = "JORNADA_COMPLETA"
    HALF_TIME = "MEDIA_JORNADA" 
    PART_TIME = "JORNADA_PARCIAL"

@dataclass
class ConstraintViolation:
    constraint_id: int
    description: str
    severity: str  # 'HIGH', 'MEDIUM', 'LOW'
    
class Day(Enum):
    MONDAY = "LUNES"
    TUESDAY = "MARTES"
    WEDNESDAY = "MIERCOLES"
    THURSDAY = "JUEVES"
    FRIDAY = "VIERNES"

class ScheduleValidator:
    """Enhanced constraint validation system with activity type support"""
    
    @staticmethod
    def get_contract_type(total_hours: int) -> ContractType:
        if total_hours >= 16:
            return ContractType.FULL_TIME
        elif total_hours >= 12:
            return ContractType.HALF_TIME
        return ContractType.PART_TIME

    @staticmethod
    def get_campus_from_room(room_code: str) -> str:
        if room_code.startswith('KAU'):
            return 'Kaufmann'
        elif room_code.startswith('HUA'):
            return 'Huayquique'
        return 'Playa Brava'

    @staticmethod
    def is_practical_activity(activity: str) -> bool:
        """Check if the activity type is practical (lab, workshop, practice)"""
        activity = ActivityType.normalize_activity(activity)
        practical_types = {
            ActivityType.LAB.value,
            ActivityType.WORKSHOP.value,
            ActivityType.PRACTICE.value
        }
        return activity in practical_types

    def validate_assignment(self, assignment: Dict, subject_info: Dict, 
                          room_info: Dict, day_schedule: List[Dict]) -> List[ConstraintViolation]:
        violations = []
        
        # Normalize activity type
        assignment['Actividad'] = ActivityType.normalize_activity(assignment['Actividad'])
        
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
            self.is_practical_activity(assignment['Actividad'])
        ):
            violations.append(ConstraintViolation(
                2,
                f"More than 2 continuous hours for {assignment['Actividad']} class",
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

        # Constraint 5: Activity-appropriate time slots
        if not self.validate_activity_time_slot(
            assignment['Actividad'],
            assignment['Bloque']
        ):
            violations.append(ConstraintViolation(
                5,
                f"Inappropriate time slot for {assignment['Actividad']}",
                'MEDIUM'
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

    def validate_continuous_blocks(self, blocks: List[int], is_practical: bool) -> bool:
        """Validate continuous blocks based on activity type"""
        if is_practical:
            # Allow up to 3 continuous blocks for practical activities
            blocks = sorted(blocks)
            continuous = 1
            for i in range(1, len(blocks)):
                if blocks[i] == blocks[i-1] + 1:
                    continuous += 1
                    if continuous > 3:
                        return False
                else:
                    continuous = 1
            return True
            
        # For theory classes, max 2 continuous blocks
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

    def validate_activity_time_slot(self, activity: str, block: int) -> bool:
        """Validate if the time slot is appropriate for the activity type"""
        activity = ActivityType.normalize_activity(activity)
        
        # Labs and workshops preferably in morning slots
        if activity in [ActivityType.LAB.value, ActivityType.WORKSHOP.value]:
            return block <= 5  # Morning to early afternoon
            
        # Tutorials and assistance preferably not in early morning
        if activity in [ActivityType.TUTORING.value, ActivityType.ASSISTANCE.value]:
            return block >= 3  # Not too early
            
        return True  # No specific constraints for other activities

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
        """Analyze schedule using actual assignments from Horarios_asignados.json"""
        analysis_data = []

        # Group assignments by professor
        for assigned_professor in assignments_data:
            prof_name = assigned_professor['Nombre']
            
            # Get original professor data for requirements
            prof_data = next(
                (p for p in self.professors_data if p['Nombre'].strip() == prof_name.strip()),
                None
            )
            
            if not prof_data:
                print(f"Warning: No matching professor data for {prof_name}")
                continue

            # Group assignments by day for constraint validation
            assignments_by_day = defaultdict(list)
            for assignment in assigned_professor.get('Asignaturas', []):
                day = assignment.get('Dia')
                if day:
                    assignments_by_day[day].append(assignment)

            # Analyze each subject separately
            original_subjects = {s['CodigoAsignatura']: s for s in prof_data.get('Asignaturas', [])}
            assigned_subjects = defaultdict(list)
            
            # Group assignments by subject code
            for assignment in assigned_professor.get('Asignaturas', []):
                subject_code = assignment.get('CodigoAsignatura')
                if subject_code:
                    assigned_subjects[subject_code].append(assignment)

            # Analyze each subject
            for subject_code, assignments in assigned_subjects.items():
                original_subject = original_subjects.get(subject_code)
                if not original_subject:
                    print(f"Warning: No matching original subject data for {subject_code}")
                    continue

                violations = []
                for assignment in assignments:
                    room_code = assignment.get('Sala')
                    room_info = self.rooms_data.get(room_code, {})
                    day = assignment.get('Dia')
                    
                    # Validate against day's schedule
                    day_schedule = assignments_by_day.get(day, [])
                    
                    assignment_violations = self.validator.validate_assignment(
                        assignment,
                        original_subject,
                        room_info,
                        day_schedule
                    )
                    violations.extend(assignment_violations)

                # Calculate metrics
                assigned_hours = len(assignments)
                expected_hours = original_subject.get('Horas', 0)
                completion_rate = (assigned_hours / expected_hours * 100) if expected_hours > 0 else 0
                
                high_violations = sum(1 for v in violations if v.severity == 'HIGH')
                medium_violations = sum(1 for v in violations if v.severity == 'MEDIUM')
                low_violations = sum(1 for v in violations if v.severity == 'LOW')
                
                # Calculate satisfaction score
                base_score = 100
                base_score -= high_violations * 20
                base_score -= medium_violations * 10
                base_score -= low_violations * 5
                satisfaction_score = max(0, min(100, base_score))

                # Add analysis entry
                analysis_data.append({
                    'Professor': prof_name,
                    'RUT': prof_data.get('RUT', ''),
                    'Subject_Code': subject_code,
                    'Subject_Name': original_subject.get('Nombre', ''),
                    'Expected_Hours': expected_hours,
                    'Assigned_Hours': assigned_hours,
                    'Completion_Rate': round(completion_rate, 2),
                    'High_Violations': high_violations,
                    'Medium_Violations': medium_violations,
                    'Low_Violations': low_violations,
                    'Satisfaction_Score': satisfaction_score,
                    'Violation_Details': '; '.join(str(v.description) for v in violations) if violations else 'None',
                    'Actual_Schedule': '; '.join(f"{a['Dia']}-B{a['Bloque']}" for a in assignments)
                })

        df = pd.DataFrame(analysis_data)
        
        # Add summary stats
        df['Activity_Distribution'] = df.apply(
            lambda x: self._get_activity_distribution(x['Subject_Code'], assignments_data),
            axis=1
        )
        
        # Calculate professor workload balance
        df['Workload_Balance'] = df.apply(
            lambda x: self._calculate_workload_balance(x['Professor'], assignments_data),
            axis=1
        )
        
        return df

    def _analyze_professor_schedule(self, prof_data: Dict, assignments: Dict, 
                                  analysis_data: List[Dict]):
        subject_assignments = defaultdict(list)
        for assignment in assignments.get('Asignaturas', []):
            subject_assignments[assignment.get('CodigoAsignatura', '')].append(assignment)

        for subject in prof_data.get('Asignaturas', []):
            code = subject.get('CodigoAsignatura', '')
            assigned = subject_assignments.get(code, [])
            
            violations = []
            for assignment in assigned:
                room_info = self.rooms_data.get(assignment.get('Sala', ''), {})
                day_schedule = [
                    a for a in assigned 
                    if a.get('Dia') == assignment.get('Dia')
                ]
                
                # Ensure Actividad exists and is normalized
                if 'Actividad' not in assignment:
                    if 'Actividad' in subject:
                        assignment['Actividad'] = subject['Actividad']
                    else:
                        assignment['Actividad'] = 'TEO'  # Default to theory
                
                assignment_violations = self.validator.validate_assignment(
                    assignment,
                    subject,
                    room_info,
                    day_schedule
                )
                violations.extend(assignment_violations)

            assigned_hours = len(assigned)
            expected_hours = subject.get('Horas', 0)
            completion_rate = (assigned_hours / expected_hours * 100) if expected_hours > 0 else 0
            
            high_violations = sum(1 for v in violations if v.severity == 'HIGH')
            medium_violations = sum(1 for v in violations if v.severity == 'MEDIUM')
            low_violations = sum(1 for v in violations if v.severity == 'LOW')
            
            # Calculate satisfaction score (0-100)
            base_score = 100
            base_score -= high_violations * 20
            base_score -= medium_violations * 10
            base_score -= low_violations * 5
            satisfaction_score = max(0, min(100, base_score))

            analysis_data.append({
                'Professor': prof_data.get('Nombre', ''),
                'RUT': prof_data.get('RUT', ''),
                'Subject_Code': code,
                'Subject_Name': subject.get('Nombre', ''),
                'Expected_Hours': expected_hours,
                'Assigned_Hours': assigned_hours,
                'Completion_Rate': round(completion_rate, 2),
                'High_Violations': high_violations,
                'Medium_Violations': medium_violations,
                'Low_Violations': low_violations,
                'Satisfaction_Score': satisfaction_score,
                'Violation_Details': '; '.join(str(v.description) for v in violations) if violations else 'None'
            })

    def _get_activity_distribution(self, subject_code: str, assignments_data: List[Dict]) -> str:
        activities = defaultdict(int)
        for prof in assignments_data:
            for assignment in prof.get('Asignaturas', []):
                if assignment.get('CodigoAsignatura') == subject_code:
                    activity = ActivityType.normalize_activity(assignment.get('Actividad', 'TEO'))
                    activities[activity] += 1
        
        return '; '.join(f"{k}: {v}" for k, v in activities.items())

    def _calculate_workload_balance(self, professor: str, assignments_data: List[Dict]) -> float:
        """Calculate workload balance score (0-100) based on daily distribution"""
        prof_data = next((p for p in assignments_data if p.get('Nombre') == professor), None)
        if not prof_data:
            return 0.0
            
        daily_load = defaultdict(int)
        for assignment in prof_data.get('Asignaturas', []):
            daily_load[assignment.get('Dia', '')] += 1
            
        if not daily_load:
            return 0.0
            
        avg_load = sum(daily_load.values()) / len(Day.__members__)
        max_deviation = max(abs(load - avg_load) for load in daily_load.values())
        
        # Score decreases with higher deviation from average
        balance_score = 100 * (1 - (max_deviation / avg_load if avg_load > 0 else 0))
        return max(0, min(100, balance_score))

def generate_detailed_report(self, output_dir: str) -> None:
    """Generate detailed analysis reports"""
    try:
        # Basic schedule analysis
        analysis_df = self.analyze_schedule([p for p in self.professors_data])
        
        # Generate summary statistics
        summary_stats = {
            'Total_Professors': len(self.professors_data),
            'Total_Subjects': sum(len(p.get('Asignaturas', [])) for p in self.professors_data),
            'Average_Completion_Rate': analysis_df['Completion_Rate'].mean(),
            'Average_Satisfaction': analysis_df['Satisfaction_Score'].mean(),
            'High_Violation_Count': analysis_df['High_Violations'].sum(),
            'Medium_Violation_Count': analysis_df['Medium_Violations'].sum(),
            'Low_Violation_Count': analysis_df['Low_Violations'].sum()
        }
        
        # Save main analysis
        analysis_df.to_excel(
            os.path.join(output_dir, 'schedule_analysis_detailed.xlsx'), 
            index=False
        )
        
        # Generate summary report
        summary_df = pd.DataFrame([summary_stats])
        summary_df.to_excel(
            os.path.join(output_dir, 'schedule_analysis_summary.xlsx'),
            index=False
        )
        
        # Generate professor-specific reports
        self._generate_professor_reports(analysis_df, output_dir)
        
        print("Analysis reports generated successfully")
        
    except Exception as e:
        print(f"Error generating reports: {str(e)}")

def _generate_professor_reports(self, analysis_df: pd.DataFrame, output_dir: str) -> None:
    """Generate individual professor reports"""
    professor_dir = os.path.join(output_dir, 'professor_reports')
    os.makedirs(professor_dir, exist_ok=True)
    
    for professor in analysis_df['Professor'].unique():
        prof_data = analysis_df[analysis_df['Professor'] == professor].copy()
        
        # Calculate professor-specific metrics
        prof_stats = {
            'Total_Subjects': len(prof_data),
            'Average_Completion': prof_data['Completion_Rate'].mean(),
            'Average_Satisfaction': prof_data['Satisfaction_Score'].mean(),
            'Total_Violations': (
                prof_data['High_Violations'].sum() +
                prof_data['Medium_Violations'].sum() +
                prof_data['Low_Violations'].sum()
            )
        }
        
        # Save professor-specific report
        prof_filename = f"{professor.replace(' ', '_')}_analysis.xlsx"
        with pd.ExcelWriter(os.path.join(professor_dir, prof_filename)) as writer:
            prof_data.to_excel(writer, sheet_name='Detailed_Analysis', index=False)
            pd.DataFrame([prof_stats]).to_excel(writer, sheet_name='Summary', index=False)

def main():
    """Main execution function"""
    # File paths
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    FULL_PATH = os.path.abspath(os.path.join(BASE_DIR, ".."))
    INPUT_DIR = os.path.join(FULL_PATH, "agent_input")
    OUTPUT_DIR = os.path.join(FULL_PATH, "agent_output")
    
    # Ensure output directory exists
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    try:
        # Load input data
        with open(os.path.join(INPUT_DIR, "inputOfProfesores.json"), 'r', encoding='utf-8') as f:
            professors_data = json.load(f)
        
        with open(os.path.join(INPUT_DIR, "inputOfSala.json"), 'r', encoding='utf-8') as f:
            rooms_data = json.load(f)
        
        with open(os.path.join(OUTPUT_DIR, "Horarios_asignados.json"), 'r', encoding='utf-8') as f:
            assignments_data = json.load(f)
        
        # Create analyzer and generate reports
        analyzer = ScheduleAnalyzer(professors_data, rooms_data)
        
        # Generate analysis using actual assignments
        analysis_df = analyzer.analyze_schedule(assignments_data)
        
        # Save detailed analysis
        analysis_df.to_excel(os.path.join(OUTPUT_DIR, 'schedule_analysis_detailed.xlsx'), index=False)
        
        # Generate summary
        summary_stats = {
            'Total_Professors': len(assignments_data),
            'Total_Assignments': sum(len(p.get('Asignaturas', [])) for p in assignments_data),
            'Total_Expected_Hours': analysis_df['Expected_Hours'].sum(),
            'Total_Assigned_Hours': analysis_df['Assigned_Hours'].sum(),
            'Average_Completion_Rate': analysis_df['Completion_Rate'].mean(),
            'Average_Satisfaction': analysis_df['Satisfaction_Score'].mean(),
            'Total_High_Violations': analysis_df['High_Violations'].sum(),
            'Total_Medium_Violations': analysis_df['Medium_Violations'].sum(),
            'Total_Low_Violations': analysis_df['Low_Violations'].sum()
        }
        
        # Save summary
        pd.DataFrame([summary_stats]).to_excel(
            os.path.join(OUTPUT_DIR, 'schedule_analysis_summary.xlsx'),
            index=False
        )
        
        print("\nAnalysis completed successfully!")
        print(f"Reports generated in: {OUTPUT_DIR}")
        
    except FileNotFoundError as e:
        print(f"Error: Required input file not found - {str(e)}")
    except json.JSONDecodeError as e:
        print(f"Error: Invalid JSON format in input file - {str(e)}")
    except Exception as e:
        print(f"Error: An unexpected error occurred - {str(e)}")

if __name__ == "__main__":
    main()