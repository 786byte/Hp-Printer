package com.justprintit.app

import android.app.Application
import com.justprintit.app.data.AppSettings
import com.justprintit.app.data.JobStore
import com.justprintit.app.data.PrinterRepository
import com.justprintit.app.print.PrintQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JustPrintItApp : Application() {
    /** Lives as long as the process, so a print keeps going when you switch apps. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { AppSettings(this) }
    val jobs by lazy { JobStore(this) }
    val printer by lazy { PrinterRepository(this, settings) }
    val queue by lazy { PrintQueue(this, appScope, settings, jobs, printer) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) { jobs.cleanupOldFiles(filesDir) }
    }
}
