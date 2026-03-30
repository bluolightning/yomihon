package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.Dictionary

interface DictionaryStorageGateway {
    suspend fun importDictionary(
        archivePath: String,
        dictionary: Dictionary,
    ): DictionaryStorageImportOutcome

    suspend fun validateImportedDictionary(
        storagePath: String,
        sampleExpression: String?,
    ): Boolean

    suspend fun refreshSearchSession()

    suspend fun clearDictionaryStorage(dictionaryId: Long)
}

data class DictionaryStorageImportOutcome(
    val success: Boolean,
    val storagePath: String?,
    val termCount: Long,
    val metaCount: Long,
    val mediaCount: Long,
)
