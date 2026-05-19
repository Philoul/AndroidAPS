package app.aaps.plugins.main.general.nfcCommands

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import dagger.android.AndroidInjection
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * Activity used specifically for background detection of unknown tags.
 * This can be enabled/disabled via settings without affecting the main NfcControlActivity.
 */
class NfcBackgroundDetectorActivity : NfcControlActivity()

open class NfcControlActivity : AppCompatActivity() {
    @Inject lateinit var nfcPlugin: NfcCommandsPlugin

    @Inject lateinit var nfcTagStore: NfcTagStore

    @Inject lateinit var aapsLogger: AAPSLogger

    @Inject lateinit var rh: ResourceHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val handled = handleIntent(intent)
            if (handled || (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
                    intent?.action != NfcAdapter.ACTION_TAG_DISCOVERED &&
                    intent?.action != NfcAdapter.ACTION_TECH_DISCOVERED)) {
                packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }?.let { startActivity(it) }
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    suspend fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        aapsLogger.debug(LTag.NFC, "Handling NFC intent: ${intent.action}")

        if (!nfcPlugin.isEnabled()) {
            aapsLogger.debug(LTag.NFC, "NFC Plugin is disabled. Ignoring tag.")
            return false
        }

        // Require a physical Tag object.
        @Suppress("DEPRECATION")
        val nfcTag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (nfcTag == null) {
            aapsLogger.debug(LTag.NFC, "Rejected intent without physical NFC tag")
            return false
        }

        val tagUid = NfcTagStore.tagUidHex(nfcTag.id) ?: return false
        aapsLogger.debug(LTag.NFC, "Scanned Tag UID: $tagUid, Techs: ${nfcTag.techList?.joinToString()}")

        return when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> handleNdefIntent(intent, tagUid)
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED -> handleTagIntent(tagUid)
            else -> false
        }
    }

    private suspend fun handleNdefIntent(intent: Intent, tagUid: String): Boolean {
        // Validation by MIME type is also done by the system filter,
        // but we double check here to be sure.
        @Suppress("DEPRECATION")
        val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val hasAapsMime = rawMsgs?.any { msg ->
            (msg as? NdefMessage)?.records?.any { record ->
                record.tnf == NdefRecord.TNF_MIME_MEDIA &&
                    String(record.type, StandardCharsets.US_ASCII) == NfcTagStore.MIME_TYPE
            } == true
        } == true

        if (hasAapsMime) {
            aapsLogger.debug(LTag.NFC, "AAPS NDEF record found for UID: $tagUid")
        } else {
            aapsLogger.debug(LTag.NFC, "No AAPS NDEF record found, falling back to UID lookup for: $tagUid")
        }

        return executeByUid(tagUid, showErrorToast = true)
    }

    private suspend fun handleTagIntent(tagUid: String): Boolean {
        aapsLogger.debug(LTag.NFC, "Tag UID lookup fallback: $tagUid")
        // Silently ignore tags not registered in My Tags — TAG_DISCOVERED/TECH_DISCOVERED fires for all tags
        // (credit cards, transit cards, etc.) and an error toast for every unknown card is
        // intrusive. Only execute if the UID is explicitly registered.
        return executeByUid(tagUid, showErrorToast = false)
    }

    private suspend fun executeByUid(tagUid: String, showErrorToast: Boolean): Boolean {
        if (nfcTagStore.isJustWritten(tagUid)) {
            aapsLogger.debug(LTag.NFC, "Ignoring tag $tagUid (recently written cooldown)")
            return true // Consider handled to prevent opening main app
        }
        val prep = nfcPlugin.prepareExecution(tagUid)
        aapsLogger.debug(LTag.NFC, "Preparation result for $tagUid: $prep")

        return when (prep) {
            is NfcPrepareResult.Error -> {
                if (showErrorToast) showToast(prep.message)
                false
            }
            is NfcPrepareResult.Ready -> {
                // Ignore REGISTER_ONLY pseudo-command if it's the only one
                val effectiveCommands = prep.commands.filter { it != "REGISTER_ONLY" }
                if (effectiveCommands.isEmpty()) {
                    aapsLogger.debug(LTag.NFC, "Tag $tagUid is registered but has no commands. Ignoring.")
                } else {
                    nfcPlugin.updateLastScanned(tagUid)
                    nfcPlugin.executeWithFeedback(effectiveCommands, prep.tagName, action = "READ")
                }
                true
            }
        }
    }

    private fun showToast(message: String) {
        runCatching {
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG)?.show()
            }
        }
    }
}
