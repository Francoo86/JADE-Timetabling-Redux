from bs4 import BeautifulSoup
from dataclasses import dataclass
from typing import List, Dict

from bs4 import BeautifulSoup
from dataclasses import dataclass
from typing import Dict, List

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

def parse_schedule(html_content: str) -> Dict[str, List[ClassBlock]]:
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
                
                class_block = ClassBlock(
                    time=time_str.strip(),
                    course_name=cell['title'].strip(),
                    course_code=code.strip(),
                    section=section.strip(),
                    block_type=block_type.replace(')', '').strip(),
                    professor=cell.get_text().strip().split('\n')[-1],
                    capacity=capacity,
                    color=block_cell['bgcolor']
                )
                
                schedule[day].append(class_block)
                print(f"Added block: {class_block.time} - {class_block.course_code}")
                
            except Exception as e:
                print(f"Error processing block: {e}")
                continue
    
    return schedule

def print_schedule(schedule: Dict[str, List[ClassBlock]], compact=False):
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

def analyze_schedule(html_content: str):
    schedule = parse_schedule(html_content)
    
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
    
    return schedule

                
import requests


TIPO = "a60e60a5"
POST_URL = "http://portal.unap.cl/kb/academica-web/horarios/horarios_PROD/presentacion/interfaz.php"
PABELLON_ID = "b62238"

# send post request to get schedule
data = {
    'pid':  PABELLON_ID,
    'tipo': TIPO
}

resp = requests.post(POST_URL, data=data)
schedule = parse_schedule(resp.text)
print(schedule)
print_schedule(schedule)

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