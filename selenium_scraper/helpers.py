# Guarda las opciones de un select dentro de un diccionario.
# Llave = Texto de la opcion.
# Valor = El valor que guarda esa opción (valga la redundancia).
from time import sleep
from web_module import Select

def cache_selector_options(selector : Select, lowered : bool = False):
    if selector is None:
        return None
    
    cache = {}
    
    # We start iterating.
    options = selector.options

    for option in options:
        opt_value = option.get_attribute("value")
        opt_txt = option.text

        if opt_value == "":
            continue

        if lowered:
            opt_txt = opt_txt.lower()

        cache[opt_txt] = opt_value

    return cache

CAMPOS = ("class", "valcampo")

# Helper function to find elements on the UNAP website.
# TODO: Cache the results in some thing.
def select_multiple_options_in_unap(driver, options, delay):
    if not isinstance(options, list): return None

    tables_sel = driver.find_elements("xpath", "//td[@class='nomcampo']")

    for i in range(0, len(tables_sel)):
        table_selector = tables_sel[i]
        select_tag = driver.get_element(table_selector, *CAMPOS)
        selector = driver.manage_selector(select_tag)

        options_by_value = cache_selector_options(selector)

        curr_opt = options[i]
        selector.select_by_value(options_by_value[curr_opt])
        sleep(delay)