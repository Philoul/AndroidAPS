package app.aaps.wear.interaction

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import app.aaps.wear.preference.WearPreferenceActivity
import dagger.android.AndroidInjection
import javax.inject.Inject

class ConfigurationActivity : WearPreferenceActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        title = "Watchface"

        val view = window.decorView as ViewGroup
        removeBackgroundRecursively(view)
        view.background = ContextCompat.getDrawable(this, R.drawable.settings_background)
        view.requestFocus()

        // Add padding to the content view for spacing from top and bottom
        val contentView = findViewById<ViewGroup>(android.R.id.content)
        contentView?.setPadding(0, 30, 0, 30)
    }

    override fun createPreferenceFragment(): PreferenceFragmentCompat {
        val configFileName = intent.action
        // Note: This appears to be legacy code. The manifest only defines the standard
        // watchface editor action, not custom XML resource names. Consider using
        // WatchfaceConfigurationActivity instead, which passes resource IDs properly.
        @Suppress("DiscouragedApi")
        val resXmlId = resources.getIdentifier(configFileName, "xml", applicationContext.packageName)
        aapsLogger.debug(LTag.WEAR, "ConfigurationActivity::createPreferenceFragment --->> getIntent().getAction() $configFileName")
        aapsLogger.debug(LTag.WEAR, "ConfigurationActivity::createPreferenceFragment --->> resXmlId $resXmlId")

        return ConfigurationFragment.newInstance(resXmlId)
    }

    override fun onPause() {
        super.onPause()
        finish()
    }

    private fun removeBackgroundRecursively(parent: View) {
        if (parent is ViewGroup)
            for (i in 0 until parent.childCount)
                removeBackgroundRecursively(parent.getChildAt(i))
        parent.background = null
    }

    /**
     * Fragment for loading watchface configuration preferences
     */
    class ConfigurationFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val resXmlId = arguments?.getInt(ARG_XML_RES_ID) ?: 0
            if (resXmlId != 0) {
                setPreferencesFromResource(resXmlId, rootKey)
            }
        }

        companion object {
            private const val ARG_XML_RES_ID = "xml_res_id"

            fun newInstance(xmlResId: Int): ConfigurationFragment {
                return ConfigurationFragment().apply {
                    arguments = Bundle().apply {
                        putInt(ARG_XML_RES_ID, xmlResId)
                    }
                }
            }
        }
    }
}
