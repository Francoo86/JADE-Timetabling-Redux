import json
from typing import Dict, List, Tuple
from dataclasses import dataclass
from collections import defaultdict

@dataclass
class RoomSchedule:
    day: str
    block: int
    professor: str
    course: str

class MASValidator:
    def __init__(self, professors_file: str, rooms_file: str, output_data: List[Dict]):
        with open(professors_file, 'r') as f:
            self.professors_data = json.load(f)
        with open(rooms_file, 'r') as f:
            self.rooms_data = json.load(f)
        self.output_data = output_data
        self.room_schedules = defaultdict(list)
        self._process_schedules()
    
    def _process_schedules(self):
        """Process all schedules into room_schedules for conflict checking"""
        for professor in self.output_data:
            for class_info in professor["Asignaturas"]:
                schedule = RoomSchedule(
                    day=class_info["Dia"],
                    block=class_info["Bloque"],
                    professor=professor["Nombre"],
                    course=class_info["CodigoAsignatura"]
                )
                self.room_schedules[class_info["Sala"]].append(schedule)

    def _get_professor_input_data(self, name: str) -> Dict:
        """Get original professor data from input file"""
        for prof in self.professors_data:
            if prof["Nombre"].strip() == name.strip():
                return prof
        return None

    def _get_room_data(self, room_code: str) -> Dict:
        """Get room data from input file"""
        for room in self.rooms_data:
            if room["Codigo"] == room_code:
                return room
        return None

    def validate_room_capacity(self) -> List[str]:
        """Validate that room capacities are sufficient for class sizes"""
        errors = []
        for professor in self.output_data:
            prof_data = self._get_professor_input_data(professor["Nombre"])
            if not prof_data:
                errors.append(f"Professor {professor['Nombre']} not found in input data")
                continue
                
            for class_info in professor["Asignaturas"]:
                room_data = self._get_room_data(class_info["Sala"])
                if not room_data:
                    errors.append(f"Room {class_info['Sala']} not found in input data")
                    continue
                    
                # Find matching course in professor's input data
                for input_course in prof_data["Asignaturas"]:
                    if input_course["CodigoAsignatura"] == class_info["CodigoAsignatura"]:
                        if input_course["Vacantes"] > room_data["Capacidad"]:
                            errors.append(
                                f"Room {class_info['Sala']} capacity ({room_data['Capacidad']}) "
                                f"is insufficient for course {class_info['CodigoAsignatura']} "
                                f"with {input_course['Vacantes']} students"
                            )
                        break
        return errors

    def validate_room_conflicts(self) -> List[str]:
        """Check for room double-booking"""
        errors = []
        for room, schedules in self.room_schedules.items():
            # Check each pair of schedules for the same room
            for i, schedule1 in enumerate(schedules):
                for schedule2 in schedules[i+1:]:
                    if (schedule1.day == schedule2.day and 
                        schedule1.block == schedule2.block and
                        schedule1 != schedule2):
                        errors.append(
                            f"Room conflict in {room} on {schedule1.day} block {schedule1.block}: "
                            f"{schedule1.professor} ({schedule1.course}) and "
                            f"{schedule2.professor} ({schedule2.course})"
                        )
        return errors

    def _should_split_into_instances(self, vacantes: int) -> bool:
        """Determine if a course should be split based on enrollment"""
        return vacantes >= 70

    def _get_required_hours_per_instance(self, input_course: Dict) -> List[int]:
        """Calculate required hours for each instance of a course"""
        if self._should_split_into_instances(input_course["Vacantes"]):
            # For courses with 70+ students, split into two equal instances
            hours_per_instance = input_course["Horas"] // 2
            return [hours_per_instance, hours_per_instance]
        else:
            # Single instance with all hours
            return [input_course["Horas"]]

    def validate_professor_hours(self) -> List[str]:
        """Validate that all required hours are assigned based on instance logic"""
        errors = []
        for professor in self.output_data:
            prof_data = self._get_professor_input_data(professor["Nombre"])
            if not prof_data:
                continue

            # Track assignments by course code and instance
            course_instances = defaultdict(lambda: defaultdict(int))
            for class_info in professor["Asignaturas"]:
                code = class_info["CodigoAsignatura"]
                instance = class_info.get("Instance", 0)
                course_instances[code][instance] += 1

            # Validate each course
            for input_course in prof_data["Asignaturas"]:
                code = input_course["CodigoAsignatura"]
                required_hours_per_instance = self._get_required_hours_per_instance(input_course)
                
                # Get actual instance assignments
                instances = sorted(course_instances[code].keys())
                
                # If no instances found at all, that's an error
                if not instances and input_course["Horas"] > 0:
                    errors.append(
                        f"No hours assigned for {professor['Nombre']} course {code} "
                        f"(required {input_course['Horas']} hours)"
                    )
                    continue

                # Check each instance has appropriate hours
                for idx, req_hours in enumerate(required_hours_per_instance):
                    if idx < len(instances):
                        assigned = course_instances[code][instances[idx]]
                        if assigned < req_hours:
                            errors.append(
                                f"Insufficient hours for {professor['Nombre']} course {code} "
                                f"instance {instances[idx]}: assigned {assigned}, required {req_hours}"
                            )
                    else:
                        errors.append(
                            f"Missing instance {idx} for {professor['Nombre']} course {code} "
                            f"(requires {req_hours} hours)"
                        )

        return errors

    def validate_campus_constraints(self) -> List[str]:
        """Validate that classes are assigned to correct campus"""
        errors = []
        for professor in self.output_data:
            prof_data = self._get_professor_input_data(professor["Nombre"])
            if not prof_data:
                continue
                
            for class_info in professor["Asignaturas"]:
                room_data = self._get_room_data(class_info["Sala"])
                if not room_data:
                    continue
                    
                for input_course in prof_data["Asignaturas"]:
                    if input_course["CodigoAsignatura"] == class_info["CodigoAsignatura"]:
                        if input_course["Campus"] != room_data["Campus"]:
                            errors.append(
                                f"Campus mismatch for {class_info['CodigoAsignatura']}: "
                                f"required {input_course['Campus']}, "
                                f"assigned to room in {room_data['Campus']}"
                            )
        
        return errors

    def validate_all(self) -> Dict[str, List[str]]:
        """Run all validations and return results"""
        return {
            "room_capacity": self.validate_room_capacity(),
            "room_conflicts": self.validate_room_conflicts(),
            "professor_hours": self.validate_professor_hours(),
            "campus_constraints": self.validate_campus_constraints()
        }

def main():
    # Example usage
    import os
    FILE_PATH = os.path.dirname(os.path.abspath(__file__))
    PROF_FILE = os.path.abspath(os.path.join(FILE_PATH, os.pardir, "agent_input", "ultimatum_profesores.json"))
    ROOM_FILE = os.path.abspath(os.path.join(FILE_PATH, os.pardir, "agent_input", "inputOfSala.json"))
    ASSIGNED_FILE = os.path.abspath(os.path.join(FILE_PATH, "Horarios_asignados.json"))

    validator = MASValidator(
        professors_file=PROF_FILE,
        rooms_file=ROOM_FILE,
        output_data=json.loads(open(ASSIGNED_FILE, 'r').read())
    )
    
    results = validator.validate_all()
    
    # Print results
    for validation_type, errors in results.items():
        print(f"\n{validation_type.upper()} VALIDATION:")
        if not errors:
            print("✓ All checks passed")
        else:
            for error in errors:
                print(f"❌ {error}")

if __name__ == "__main__":
    main()