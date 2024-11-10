from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import Select
from selenium.webdriver.support.ui import WebDriverWait
from selenium.common.exceptions import NoSuchElementException
from selenium.common.exceptions import UnexpectedTagNameException
from selenium.webdriver.support import expected_conditions as EC
from time import sleep
from bs4 import BeautifulSoup

# TODO: Change raise statements to avoid the program 'crashing'.

class WebBrowser:
    def __init__(self, browser_name, headless):
        browser_name = browser_name.lower()

        self.browser_name = browser_name
        self.browser = None

        browsers_dict = {
            "firefox" : {
                "init" : webdriver.Firefox,
                "opt": webdriver.FirefoxOptions
            },

            "edge" : {
                "init": webdriver.Edge,
                "opt": webdriver.EdgeOptions
            },
            
            "chrome": {
                "init": webdriver.Chrome,
                "opt": webdriver.ChromeOptions,
            },

            "chromium" : {
                "init": webdriver.ChromiumEdge,
                "opt": webdriver.EdgeOptions
            },

            "safari": {
                "init": webdriver.Safari,
            },

            "iexplore": {
                "init": webdriver.Ie,
                "opt": webdriver.IeOptions
            }
        }

        self.criteria_select = {
            "id": By.ID,
            "name": By.NAME,
            "class": By.CLASS_NAME,
            "xpath": By.XPATH,
            "css_selector": By.CSS_SELECTOR,
            "tag": By.TAG_NAME,
            "partial_link": By.PARTIAL_LINK_TEXT,
            "link_text": By.PARTIAL_LINK_TEXT,
        }

        if browser_name in browsers_dict:
            selected_browser = browsers_dict[browser_name]

            if "opt" in selected_browser and headless == True:
                # Create options object.
                options = selected_browser["opt"]()
                options.headless = True

                self.browser = selected_browser["init"](options=options)
            else:
                self.browser = selected_browser["init"]()
        else:
            raise ValueError(f"Navegador no soportado: {browser_name}")

    def switch_to_another_window(self, title):
        # Switch to the new window
        original_window = self.browser.current_window_handle
        for window_handle in self.browser.window_handles:
            if window_handle != original_window:
                self.browser.switch_to.window(window_handle)
                break

        return self.wait_callback(EC.title_contains(title))

    def quit(self):
        self.browser.quit()

    def go_to_url(self, URL):
        self.browser.get(URL)

    def get_url(self):
        return self.browser.current_url
    
    def wait_callback(self, callback):
        try:
            wait = WebDriverWait(self.browser, 10)
            wait.until(callback)
            return True
        except:
            print("ERROR: The page can't be loaded.")
        
        return False

    def is_on_criteria(self, criteria):
        if not criteria in self.criteria_select:
            raise ValueError("That criteria doesn't exist.")
        
        return True
    
    def wait_to_fully_load(self, criteria, name):
        self.is_on_criteria(criteria)

        return self.wait_callback(EC.presence_of_element_located((self.criteria_select[criteria], name)))

    # We need delay because for some reasons browsers crashes. IE: Edge and Chrome.
    # I think its because the page is not fully loaded?
    # Changing it in the future.
    def run_scripts_delayed(self, list_scripts, delay):
        if not isinstance(list_scripts, list):
            raise ValueError("Not a script list.")
        
        delay = max(delay, 0.1)

        for script_func in list_scripts:
            self.browser.execute_script(script_func)
            sleep(delay)

    # As the name says runs the script directly.
    # The script needs to have semicolons.
    def run_script_directly(self, script):
        self.browser.execute_script(script)

        sleep(0.5)

    def get_page_element(self, criteria, name):
        self.is_on_criteria(criteria)

        return self.get_element(self.browser, criteria, name)
    
    def get_element(self, obj, criteria, name):
        # Change it to browser.
        obj = obj if obj else self.browser

        self.is_on_criteria(criteria)

        locator = self.criteria_select[criteria]
        find_element = getattr(obj, "find_element")

        try:
            # Reciclying functions because yes.
            elements = find_element(locator, name)
            return elements
        except NoSuchElementException as e:
            print("[ERROR]: Not found element.", e)

        return None
    
    # TODO: Add exceptions.
    def find_elements(self, criteria, name):
        self.is_on_criteria(criteria)

        return self.browser.find_elements(self.criteria_select[criteria], name)
    
    def manage_selector(self, obj):
        try:
            select_obj = Select(obj)
            return select_obj
        except UnexpectedTagNameException as e:
            print("[ERROR]: Not a valid tag to input.", e)
        except:
            print("[ERROR]: Unknown error")

        return None
    
    # Helper function to print selector stuff.
    def print_selector_stuff(self, selector):
        options = selector.options
        
        for option in options:
            option_value = option.get_attribute("value")
            option_text = option.text
            print(f"Option value: {option_value}, option text: {option_text}")

    
    def get_page_scrap(self):
        html = self.browser.page_source
        return BeautifulSoup(html, "html.parser")