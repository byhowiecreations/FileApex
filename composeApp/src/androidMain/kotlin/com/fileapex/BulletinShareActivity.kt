package com.fileapex

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.fileapex.platform.AndroidShareBulletin
import com.fileapex.platform.AndroidShareIntake
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Text selection → Bulletin Board (PROCESS_TEXT). Share sheet uses Direct Share bubble on MainActivity. */
class BulletinShareActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show(com.fileapex.i18n.AppI18n.t("fileapex_still_starting"))
            finish()
            return
        }
        handleProcessTextIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProcessTextIntent(intent)
    }

    private fun handleProcessTextIntent(intent: Intent?) {
        val textIntent = intent ?: run {
            finish()
            return
        }
        scope.launch {
            runCatching {
                val text = AndroidShareIntake.extractSharedText(textIntent)
                if (text.isNullOrBlank()) error(com.fileapex.i18n.AppI18n.t("nothing_to_post"))
                AndroidShareBulletin.ingestShareIntent(this@BulletinShareActivity, textIntent)
            }.onSuccess {
                BriefToast.show(com.fileapex.i18n.AppI18n.t("posted_to_bulletin"))
            }.onFailure { error ->
                BriefToast.show(error.message ?: com.fileapex.i18n.AppI18n.t("could_not_post_bulletin"))
            }
            finish()
        }
    }
}
