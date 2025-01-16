import json
import math
import os

def split_json_file(input_file, output_file1, output_file2):
    """
    Split a JSON file into two separate files of roughly equal size.
    
    Args:
        input_file (str): Path to the input JSON file
        output_file1 (str): Path to the first output file
        output_file2 (str): Path to the second output file
    """
    # Read the input JSON file
    with open(input_file, 'r', encoding="utf-8") as f:
        data = json.load(f)
    
    if isinstance(data, list):
        # If the JSON contains a list/array
        middle_index = math.ceil(len(data) / 2)
        first_half = data[:middle_index]
        second_half = data[middle_index:]
        
    elif isinstance(data, dict):
        # If the JSON contains an object/dictionary
        items = list(data.items())
        middle_index = math.ceil(len(items) / 2)
        first_half = dict(items[:middle_index])
        second_half = dict(items[middle_index:])
    
    else:
        raise ValueError("Input JSON must be either an array or an object")
    
    # Write the split data to output files
    with open(output_file1, 'w', encoding="utf-8") as f1:
        json.dump(first_half, f1, indent=2)
    
    with open(output_file2, 'w', encoding="utf-8") as f2:
        json.dump(second_half, f2, indent=2)

# Example usage
if __name__ == "__main__":
    FILE_PATH = os.path.dirname(os.path.abspath(__file__))
    AGENT_INPUT_FOLDER = os.path.join(FILE_PATH, "..", "agent_input") 
    
    splittable = os.path.join(AGENT_INPUT_FOLDER, "first_half_profesores.json")
    part1 = os.path.join(AGENT_INPUT_FOLDER, "fhp_part1.json")
    part2 = os.path.join(AGENT_INPUT_FOLDER, "fhp_part2.json")
    
    try:
        split_json_file(
            input_file=splittable,
            output_file1=part1,
            output_file2=part2
        )
        print("Successfully split the JSON file!")
    except Exception as e:
        print(f"An error occurred: {str(e)}")