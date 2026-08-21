package com.fileapex.platform

import android.content.Context
import android.content.Intent
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidShareBulletin {
    suspend fun ingestShareIntent(context: Context, intent: Intent) = withContext(Dispatchers.IO) {
        val sharedText = AndroidShareIntake.extractSharedText(intent)?.trim().orEmpty()
        val uris = AndroidShareIntake.extractStreamUris(intent)
        when {
            sharedText.isNotBlank() && (
                AndroidShareIntake.isWebPageLinkShare(intent) ||
                    intent.type?.trim().orEmpty().startsWith("text/")
                ) -> {
                FileApexServices.bulletinSyncEngine.ingestSharedText(sharedText)
            }
            uris.isNotEmpty() -> {
                val payload = AndroidShareIntake.stageShareUris(context, uris)
                val file = payload.files.first()
                FileApexServices.bulletinSyncEngine.ingestSharedFile(
                    absolutePath = file.absolutePath,
                    fileName = file.fileName,
                    sizeBytes = file.sizeBytes,
                    caption = sharedText
                )
            }
            sharedText.isNotBlank() -> {
                FileApexServices.bulletinSyncEngine.ingestSharedText(sharedText)
            }
            else -> error("Nothing to post on the Bulletin Board")
        }
    }
}
