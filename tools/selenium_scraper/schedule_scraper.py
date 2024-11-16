from bs4 import BeautifulSoup
from dataclasses import dataclass
from typing import List, Dict

from bs4 import BeautifulSoup
from dataclasses import dataclass
from typing import Dict, List

import requests
import threading

from dotenv import load_dotenv
import os

load_dotenv()

IQQ_CAMPUS_CLASSROOMS = os.getenv("IQQ_CAMPUS")
SCHEDULE_POST_URL = os.getenv("SCHEDULE_INFO_URL")

@dataclass
class ClassBlock:
    time: str
    course_name: str
    course_code: str
    section: str
    block_type: str
    professor: str
    capacity: int
    color: str
    
class ScheduleScraper:
    def __init__(self, selected_classrooms : List[str] = None):
        # speed up requests by reusing the same session
        self.unap_req = requests.Session()
        self.classrooms = {}
        self.classroom_data = {}
        
        # this is a filter to only get the schedules for the selected classrooms
        self.selected_classrooms = selected_classrooms
        
        self.__fetch_classrooms()
    
    def __fetch_classrooms(self):
        print("[INFO] Obteniendo salas...")
        self.classrooms = {}
        
        resp = self.unap_req.get(IQQ_CAMPUS_CLASSROOMS)

        if resp.status_code != 200:
            print("[ERROR] No se pudieron obtener las salas")
            raise Exception("No se pudieron obtener las salas")
        
        soup = BeautifulSoup(resp.text, 'lxml')
        selector = soup.find('select', {'id': 'sala'})
        
        for option in selector.find_all('option'):
            sala_id = option['value']
            if sala_id == '':
                continue
            
            sala_name = option.text
            
            if self.selected_classrooms and sala_name not in self.selected_classrooms:
                continue
            
            self.classrooms[sala_name] = sala_id
            
    def get_schedules_per_classroom(self):
        for sala_name, sala_id in self.classrooms.items():
            print(f"[INFO] Obteniendo horarios para sala: {sala_name}")
    
            post_data = {
                'pid': sala_id,
                'tipo': 'a60e60a5'
            }
            
            req = self.unap_req.post(SCHEDULE_POST_URL, data=post_data)
            
            if req.status_code != 200:
                print(f"[ERROR] No se pudieron obtener los horarios para la sala {sala_name}")
                continue
            
            schedule = self.__parse_schedule(req.text)
            
            self.classroom_data[sala_name] = schedule
            
    def print_schedule_for_classroom(self, sala_name):
        schedule = self.classroom_data.get(sala_name)
        
        if not schedule:
            print(f"[ERROR] No se encontraron horarios para la sala {sala_name}")
            return
        
        self.__print_schedule(schedule)
    
    def __parse_schedule(self, html_content: str) -> Dict[str, List[ClassBlock]]:
        soup = BeautifulSoup(html_content, 'html.parser')
        
        # First, find the main table
        main_table = soup.find('table', attrs={'align': 'center', 'style': True})
        if not main_table:
            print("Could not find main table")
            return {}
            
        # Get days from the header row
        header_row = main_table.find('tr', class_='tittabla')
        if not header_row:
            print("Could not find header row")
            return {}
            
        days = [th.text.strip() for th in header_row.find_all('td', class_='style0')[1:]]  # Skip 'Hrs'
        schedule = {day: [] for day in days}
        
        # Process each day directly from the table
        for day_idx, day in enumerate(days):
            print(f"\nProcessing {day}:")
            
            # Find the corresponding column for this day using XPath-like navigation
            day_selector = f"td[valign='top']:nth-of-type({day_idx + 2})"  # +2 because of 1-based index and skipping first column
            day_column = main_table.select_one(f"tr.valcampo > {day_selector}")
            
            if not day_column:
                print(f"Could not find column for {day}")
                continue
                
            # Process each class block in this day's column
            for block_cell in day_column.find_all('td', bgcolor=True):
                try:
                    # Get the class info table
                    class_table = block_cell.find('table', attrs={'height': '100%', 'width': '100%'})
                    if not class_table:
                        continue
                        
                    cell = class_table.find('td', class_='letra_peq')
                    if not cell:
                        continue
                        
                    # Get time and capacity
                    time_label = cell.find('label', style=True).text.strip()
                    time_str, alu_str = time_label.split('(Alu')
                    capacity = int(alu_str.replace(')', '').strip())
                    
                    # Get course info
                    course_info = cell.find('b').text.strip()
                    code_section, block_type = course_info.split('(')
                    code, section = code_section.split('-')
                    
                    # for professor we will get the after the latest br tag
                    # we will split the text by new line and get the last element
                    prof_content = cell.get_text(separator=" ", strip=True)
                    professor = prof_content.split()[-2] + " " + prof_content.split()[-1]
                    
                    class_block = ClassBlock(
                        time=time_str.strip(),
                        course_name=cell['title'].strip(),
                        course_code=code.strip(),
                        section=section.strip(),
                        block_type=block_type.replace(')', '').strip(),
                        professor=professor,
                        capacity=capacity,
                        color=block_cell['bgcolor']
                    )
                    
                    schedule[day].append(class_block)
                    print(f"Added block: {class_block.time} - {class_block.course_code}")
                    
                except Exception as e:
                    print(f"Error processing block: {e}")
                    continue
        
        return schedule
    
    def __print_schedule(self, schedule: Dict[str, List[ClassBlock]], compact=False):
        """Print the schedule in a readable format."""
        for day, blocks in schedule.items():
            if blocks:  # Only print days that have classes
                print(f"\n=== {day} ===")
                # Sort blocks by start time
                blocks.sort(key=lambda x: x.time.split('-')[0])
                for block in blocks:
                    if compact:
                        print(f"{block.time}: {block.course_code} ({block.block_type}) - {block.professor}")
                    else:
                        print(f"\nTime: {block.time}")
                        print(f"Course: {block.course_name} ({block.course_code}-{block.section})")
                        print(f"Type: {block.block_type}")
                        print(f"Professor: {block.professor}")
                        print(f"Capacity: {block.capacity}")
                        print(f"Color: {block.color}")
                    
    def __analyze_schedule(self, schedule: Dict[str, List[ClassBlock]]):
        print("\nSCHEDULE SUMMARY:")
        total_classes = 0
        all_professors = set()
        all_courses = set()
        
        for day, blocks in schedule.items():
            if blocks:
                print(f"\n{day}:")
                print(f"- Classes: {len(blocks)}")
                print(f"- Professors: {', '.join(sorted({b.professor for b in blocks}))}")
                print(f"- Courses: {', '.join(sorted({b.course_code for b in blocks}))}")
                
                total_classes += len(blocks)
                all_professors.update(b.professor for b in blocks)
                all_courses.update(b.course_code for b in blocks)
        
        print(f"\nTotal Statistics:")
        print(f"- Total Classes: {total_classes}")
        print(f"- Total Unique Professors: {len(all_professors)}")
        print(f"- Total Unique Courses: {len(all_courses)}")
    
    def analyze_schedule_by_classroom(self, sala_name):
        schedule = self.classroom_data.get(sala_name)
        
        if not schedule:
            print(f"[ERROR] No se encontraron horarios para la sala {sala_name}")
            return
        
        self.__analyze_schedule(schedule)

# Example of getting specific information
def get_course_schedule(schedule: Dict[str, List[ClassBlock]], course_code: str):
    """Find all instances of a specific course in the schedule."""
    course_times = []
    for day, blocks in schedule.items():
        for block in blocks:
            if block.course_code == course_code:
                course_times.append((day, block))
    return course_times

def get_professor_schedule(schedule: Dict[str, List[ClassBlock]], professor_name: str):
    """Find all classes taught by a specific professor."""
    prof_schedule = []
    for day, blocks in schedule.items():
        for block in blocks:
            if professor_name.lower() in block.professor.lower():
                prof_schedule.append((day, block))
    return prof_schedule


ss = ScheduleScraper(selected_classrooms=["LC6"])
ss.get_schedules_per_classroom()

ss.print_schedule_for_classroom("LC6")