package mihon.domain.dictionary.model

/**
 * Exception thrown when dictionary import fails.
 */
class DictionaryImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
