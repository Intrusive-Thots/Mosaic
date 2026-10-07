package com.intrusivethots.mosaic.engine

class EmptyLibraryException : Exception("Add at least one tile image before generating a mosaic.")

class InvalidTargetException : Exception("The target image could not be read.")

class InsufficientStorageException : Exception("Not enough free storage to save this mosaic.")

class StalePlanException : Exception("The saved collage does not match these pictures, so it was left unchanged.")
