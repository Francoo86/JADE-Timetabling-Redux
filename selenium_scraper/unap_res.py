
# TODO: Add teachers and stuff.
class Results:
    # Consider each one a dict where it saves the text and value.
    # Currently i have issues with classrooms as it changes each time.
    # So we can cache the values select for each classrooms but the issue is on the AJAX req.
    def __init__(self, campuses, locals, spec_locals):
        self.campuses = campuses
        self.locals = locals
        self.spec_locals = spec_locals

