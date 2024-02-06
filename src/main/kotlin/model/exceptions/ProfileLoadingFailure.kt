package model.exceptions

import java.lang.Exception

class ProfileLoadingFailure(s: String) : Exception(s)
