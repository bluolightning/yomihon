package eu.kanade.tachiyomi.data.dictionary

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface DictionaryImportCoordinator {
    fun startFromUri(uri: Uri)

    fun startFromUrl(url: String)

    fun isRunningFlow(): Flow<Boolean>
}

class WorkManagerDictionaryImportCoordinator(
    private val application: Application,
) : DictionaryImportCoordinator {

    override fun startFromUri(uri: Uri) {
        DictionaryImportJob.start(application, uri)
    }

    override fun startFromUrl(url: String) {
        DictionaryImportJob.start(application, url)
    }

    override fun isRunningFlow(): Flow<Boolean> {
        return DictionaryImportJob.isRunningFlow(application)
    }
}
