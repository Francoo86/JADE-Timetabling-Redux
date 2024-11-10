# import these things.
from classrooms_module import get_classrooms, UNAP_CLASSROOM_DATA, initialize_url
from export_classrooms import make_nice_json, create_json

# Inicializar pagina de seleccion de salas.
def save_classrooms(filename : str, nice_json: bool = False):
    classroom_scrapper = initialize_url("firefox", UNAP_CLASSROOM_DATA, False)
    
    # The classrooms.
    classroom_dict = get_classrooms(classroom_scrapper, 0.75, very_nested=False)
    classroom_scrapper.quit()

    if nice_json:
        nice_filename = "nice_" + filename
        make_nice_json(classroom_dict, nice_filename)
    else:
        create_json(filename, classroom_dict)
    
save_classrooms("salas_unap_final_test.json", True)

# TODO: Add those classrooms to generator based on their campus location.