# Same directory.
from classrooms_module import CURRENT_DIR, os, json

def create_json(filename : str, dict_to_json : dict):
    with open(os.path.join(CURRENT_DIR, filename), "w") as outfile:
        json.dump(dict_to_json, outfile, indent=4)

def json2dict(filename : str) -> dict:
    with open(os.path.join(CURRENT_DIR, filename)) as json_dict:
        read_dict = json.load(json_dict)

        return read_dict

# thanks chatgpt
def remove_empty_dicts(data):
    if isinstance(data, dict):
        # Iterate over a copy of the keys to avoid modifying the dictionary during iteration
        for key in list(data.keys()):
            if isinstance(data[key], dict):
                remove_empty_dicts(data[key])  # Recursively remove empty dicts in nested dictionaries
                if not data[key]:  # If the nested dictionary is empty, remove it
                    del data[key]
            elif not data[key]:  # If the value is an empty dictionary, remove it
                del data[key]
    return data

def remove_level(data, level):
    if isinstance(data, dict):
        # Iterate over a copy of the keys to avoid modifying the dictionary during iteration
        for key in list(data.keys()):
            if key == level and isinstance(data[key], dict):
                # Move the values from the specified level to the parent level
                for subkey, value in data[key].items():
                    data[subkey] = value
                del data[key]  # Remove the specified level
            elif isinstance(data[key], dict):
                remove_level(data[key], level)  # Recursively remove the specified level in nested dictionaries
    return data

def make_nice_json(input: str | dict, output_filename : str):
    if isinstance(input, str):
        input = json2dict(input)

    jsonable_dict = remove_level(remove_empty_dicts(input), "Locality")

    create_json(output_filename, jsonable_dict)


