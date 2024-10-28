# d0, d1, d2, d3
# d0 => Permite el cambio de localidad.
# d1 => Campus.
# d2 => Cambiar de sala.
# d3 => Enviar la solicitud GET.
from web_module import WebBrowser
import helpers

# IQUIQUE_DATA = ["obtLoca('1')", "obtCamp('1')", "obtSala('CC')"]
UNAP_URL_SCHED = "http://portal.unap.cl/kb/academica-web/comun/interfaz-gene_1.php?tipo=a6&redir=da407cabd270a5d1fce232f9464b7b2e827c2666a3533c9fabbf9bdb4a9a740c41f3b6ecd48bd61f137559073fc2decadb95bfbc965a28e3be0b9a08a9113e44e395e6ef50c3012f53cde9943b7706868224&otros=d11b65b4cf3e97dcbef6"

def start_browser(browser, URL, headless):
    driver = WebBrowser(browser, headless)
    driver.go_to_url(URL)

    return driver

SALA_LP = ["iquique", "iquique", "playa brava", "lp"]

def start_submitting(driver, options):
    if not isinstance(driver, WebBrowser): return

    if not driver.wait_to_fully_load("tag", "table"):
        print("Can't go to that page.")

    helpers.select_multiple_options_in_unap(driver, options, 0.3)

    form = driver.get_page_element("id", "d3")
    btn = driver.get_element(form, "class", "valcampo")

    btn.click()

    driver.switch_to_another_window("Universidad Arturo Prat")

driver = start_browser("Chrome", UNAP_URL_SCHED, False)
start_submitting(driver, SALA_LP)

# TODO: Scrapear adecuadamente.