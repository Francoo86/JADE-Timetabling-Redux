# d0, d1, d2, d3
# d0 => Permite el cambio de localidad.
# d1 => Campus.
# d2 => Cambiar de sala.
# d3 => Enviar la solicitud GET.

from typing import Iterable
from web_module import WebBrowser
from time import sleep
import os
import json

CURRENT_DIR = os.path.dirname(__file__)

UNAP_CLASSROOM_DATA = "http://portal.unap.cl/kb/academica-web/comun/interfaz-gene_1.php?tipo=a6&redir=da407cabd270a5d1fce232f9464b7b2e827c2666a3533c9fabbf9bdb4a9a740c41f3b6ecd48bd61f137559073fc2decadb95bfbc965a28e3be0b9a08a9113e44e395e6ef50c3012f53cde9943b7706868224&otros=d11b65b4cf3e97dcbef6"

def initialize_url(browser : str, URL : str, headless : bool):
    driver = WebBrowser(browser, headless)
    driver.go_to_url(URL)

    return driver

data_to_save = ["City", "Locality", "Campus", "Classrooms"]
IDS_GREATER_THAN_CITY = ["d0", "d1", "d2"]

# Recursion-ish thing.
# TODO: Refactor and remove weird things.
def get_classrooms(driver : WebBrowser, delay : float, table_data : Iterable = None, state : int = 0, deep_dict : dict = None, very_nested : bool = True) -> dict:
    if table_data is None:
        table_data = driver.find_elements("xpath", "//td[@class='nomcampo']")

    if deep_dict is None:
        deep_dict = {}

    for elem in table_data:
        select_tag = driver.get_element(elem, "class", "valcampo")
        selector = driver.manage_selector(select_tag)

        if selector is None:
            continue
        
        id_elem = elem.get_attribute("id")

        # WORKAROUND: Magic code to stop the recursion when doing things wrong.
        # this works properly.
        if id_elem in IDS_GREATER_THAN_CITY and state == 0:
            print("[WARNING] No more classrooms to fetch.")
            return deep_dict
        
        options = selector.options

        for option in options:
            text = option.text

            if text.strip() == "Seleccione":
                continue
    
            value = option.get_attribute("value")

            # Save stuff based on current level.
            index_save = data_to_save[state]

            selector.select_by_value(value)
    
            # ajax pls
            if state > 2:
                list_check = deep_dict.get(index_save)
        
                if list_check is None or not isinstance(list_check, list):
                    deep_dict[index_save] = []
                
                # We are running this again?
                if text in deep_dict[index_save]:
                    continue

                deep_dict[index_save].append(text)
            else:
                # Make a delay each time we are searching for campuses and shit.
                sleep(delay)
    
                tab_to_use = driver.find_elements("id", f"d{state}")

                # Magical code again.
                if not very_nested and (state >= 0 and state < 2):
                    get_classrooms(driver, delay, tab_to_use, state + 1, deep_dict)
                    continue

                dict_check = deep_dict.get(index_save)
    
                if dict_check is None:                    
                    deep_dict[index_save] = {}

                if text not in deep_dict[index_save]:
                    deep_dict[index_save][text] = {}
                                    
                get_classrooms(driver, delay, tab_to_use, state + 1, deep_dict[index_save][text])

    # this weird thingy again. although it doesn't work as expected.
    return deep_dict
