package app.aaps.plugins.insulin

import androidx.fragment.app.FragmentActivity
import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.insulin.Insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.fromJson
import app.aaps.core.objects.extensions.toJson
import app.aaps.core.ui.toast.ToastUtils
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.pow

/**
 * Created by Philoul on 29.12.2024.
 *
 *
 */

@Singleton
class InsulinPlugin @Inject constructor(
    val preferences: Preferences,
    rh: ResourceHelper,
    val profileFunction: ProfileFunction,
    val rxBus: RxBus,
    aapsLogger: AAPSLogger,
    val config: Config,
    val hardLimits: HardLimits,
    val uiInteraction: UiInteraction,
    val uel: UserEntryLogger,
    val activePlugin: ActivePlugin
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.INSULIN)
        .fragmentClass(InsulinFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_insulin)
        .pluginName(app.aaps.core.interfaces.R.string.insulin_plugin)
        .shortName(app.aaps.core.interfaces.R.string.insulin_shortname)
        .setDefault(true)
        .visibleByDefault(true)
        .enableByDefault(true)
        .neverVisible(config.AAPSCLIENT)
        .description(R.string.description_insulin_plugin),
    aapsLogger, rh
), Insulin {

    override val id = InsulinType.UNKNOWN
    override val friendlyName get(): String = rh.gs(app.aaps.core.interfaces.R.string.insulin_plugin)

    override val iCfg: ICfg
        get() {
            val profile = profileFunction.getProfile()
            return profile?.iCfg()?.also { iCfg ->
                if (iCfg.getPeak() < hardLimits.minPeak() || iCfg.getPeak() > hardLimits.maxPeak())
                    iCfg.setPeak(getDefaultPeak())
            } ?: insulins[0]
        }

    lateinit var currentInsulin: ICfg
    private var lastWarned: Long = 0
    var insulins: ArrayList<ICfg> = ArrayList()
    val defaultInsulinIndex: Int
        get() {
            iCfg.let {
                insulins.forEachIndexed { index, iCfg ->
                    if (iCfg.isEqual(it)) {
                        return index
                    }
                }
            }
            return 0
        }
    var currentInsulinIndex = 0
    val numOfInsulins get() = insulins.size
    var isEdited: Boolean = false

    override fun onStart() {
        super.onStart()
        loadSettings()
    }

    override fun insulinList(): ArrayList<CharSequence> {
        val ret = ArrayList<CharSequence>()
        insulins.forEach { ret.add(it.insulinLabel) }
        return ret
    }

    override fun setDefault(iCfg: ICfg) {
        if (iCfg.getPeak() >= hardLimits.minPeak() && iCfg.getPeak() <= hardLimits.maxPeak()) {
            preferences.put(IntNonKey.InsulinOrefPeak, iCfg.getPeak())
            preferences.put(IntNonKey.InsulinTemplate, if (iCfg.insulinTemplate != 0) iCfg.insulinTemplate else Insulin.InsulinType.fromPeak(iCfg.insulinPeakTime).value)
        }
    }

    override fun getOrCreateInsulin(iCfg: ICfg): ICfg {
        // First Check insulin with hardlimits, and set default value if not consistent
        if (iCfg.getPeak() < hardLimits.minPeak() || iCfg.getPeak() > hardLimits.maxPeak())
            iCfg.setPeak(getDefaultPeak())
        if (iCfg.getDia() < hardLimits.minDia() || iCfg.getDia() > hardLimits.maxDia())
            iCfg.setDia(getDefaultDia())
        insulins.forEachIndexed { index, it ->
            if (iCfg.isEqual(it)) {
                return it
            }
        }
        return addNewInsulin(iCfg.also {
            if (it.insulinTemplate == 0)
                it.insulinTemplate = InsulinType.fromPeak(it.insulinPeakTime).value
        })
    }

    fun getDefaultPeak(): Int {
        val template = InsulinType.fromInt(preferences.get(IntNonKey.InsulinTemplate))
        return when (template) {
            InsulinType.OREF_FREE_PEAK -> preferences.get(IntNonKey.InsulinOrefPeak)
            else                       -> template.peak
        }
    }

    fun getDefaultDia(): Double = if (numOfInsulins == 0) InsulinType.OREF_RAPID_ACTING.dia else insulins[defaultInsulinIndex].getDia()

    fun setCurrent(iCfg: ICfg): Int {
        // First Check insulin with hardlimits, and set default value if not consistent
        insulins.forEachIndexed { index, it ->
            if (iCfg.isEqual(it)) {
                currentInsulinIndex = index
                currentInsulin = currentInsulin().deepClone()
                isEdited = currentInsulin.insulinTemplate == 0
                return index
            }
        }
        addNewInsulin(iCfg)
        currentInsulin = currentInsulin().deepClone()
        isEdited = currentInsulin.insulinTemplate == 0
        return insulins.size - 1
    }

    override fun getInsulin(insulinLabel: String): ICfg {
        insulins.forEach {
            if (it.insulinLabel == insulinLabel)
                return it
        }
        aapsLogger.debug(LTag.APS, "Insulin $insulinLabel not found, return default insulin ${insulins[defaultInsulinIndex].insulinLabel}")
        return insulins[defaultInsulinIndex]
    }

    fun insulinTemplateList(): ArrayList<CharSequence> {
        val ret = ArrayList<CharSequence>()
        ret.add(rh.gs(InsulinType.OREF_RAPID_ACTING.label))
        ret.add(rh.gs(InsulinType.OREF_ULTRA_RAPID_ACTING.label))
        ret.add(rh.gs(InsulinType.OREF_LYUMJEV.label))
        ret.add(rh.gs(InsulinType.OREF_FREE_PEAK.label))
        return ret
    }

    fun sendShortDiaNotification(dia: Double) {
        // Todo Check if we need this kind of function to send notification
        if (System.currentTimeMillis() - lastWarned > 60 * 1000) {
            lastWarned = System.currentTimeMillis()
            uiInteraction.addNotification(Notification.SHORT_DIA, String.format(notificationPattern, dia, hardLimits.minDia()), Notification.URGENT)
        }
    }

    private val notificationPattern: String
        get() = rh.gs(R.string.dia_too_short)

    @Synchronized
    fun addNewInsulin(newICfg: ICfg): ICfg {
        if (newICfg.insulinLabel == "" || insulinLabelAlreadyExists(newICfg.insulinLabel))
            newICfg.insulinLabel = createNewInsulinLabel(newICfg)
        val newInsulin = newICfg.deepClone()
        insulins.add(newInsulin)
        uel.log(Action.NEW_INSULIN, Sources.Insulin, value = ValueWithUnit.SimpleString(newInsulin.insulinLabel))
        currentInsulinIndex = insulins.size - 1
        currentInsulin = newInsulin.deepClone()
        storeSettings()
        return newInsulin
    }

    @Synchronized
    fun removeCurrentInsulin(activity: FragmentActivity?) {
        // activity included to include PopUp or Toast when Remove can't be done (default insulin or insulin used within profile
        // Todo include Remove authorization and message
        val insulinRemoved = currentInsulin().insulinLabel
        insulins.removeAt(currentInsulinIndex)
        uel.log(Action.INSULIN_REMOVED, Sources.Insulin, value = ValueWithUnit.SimpleString(insulinRemoved))
        currentInsulinIndex = defaultInsulinIndex
        currentInsulin = currentInsulin().deepClone()
        storeSettings()
    }

    fun createNewInsulinLabel(iCfg: ICfg, currentIndex: Int = -1, template: InsulinType? = null): String {
        val template = template ?: InsulinType.fromPeak(iCfg.insulinPeakTime)
        var insulinLabel = when (template) {
            InsulinType.OREF_FREE_PEAK -> "${rh.gs(template.label)}_${iCfg.getPeak()}_${iCfg.getDia()}"
            else                       -> "${rh.gs(template.label)}_${iCfg.getDia()}"
        }
        if (insulinLabelAlreadyExists(insulinLabel, currentIndex)) {
            for (i in 1..10000) {
                if (!insulinLabelAlreadyExists("${insulinLabel}_$i", currentIndex)) {
                    insulinLabel = "${insulinLabel}_$i"
                    break
                }
            }
        }
        return insulinLabel
    }

    private fun insulinLabelAlreadyExists(insulinLabel: String, currentIndex: Int = -1): Boolean {
        insulins.forEachIndexed { index, insulin ->
            if (index != currentIndex) {
                if (insulin.insulinLabel == insulinLabel) {
                    return true
                }
            }
        }
        return false
    }

    @Synchronized
    fun loadSettings() {
        val jsonString = preferences.get(StringKey.InsulinConfiguration)
        try {
            JSONObject(jsonString).let {
                applyConfiguration(it)
            }
        } catch (_: Exception) {
            //
        }
    }

    @Synchronized
    fun storeSettings() {
        preferences.put(StringKey.InsulinConfiguration, configuration().toString())
        isEdited = false
    }

    override fun iobCalcForTreatment(bolus: BS, time: Long, iCfg: ICfg): Iob {
        if (iCfg.getPeak() < hardLimits.minPeak() || iCfg.getPeak() > hardLimits.maxPeak())
            iCfg.setPeak(getDefaultPeak())
        if (iCfg.getDia() < hardLimits.minDia() || iCfg.getDia() > hardLimits.maxDia())
            iCfg.setDia(getDefaultDia())
        return iobCalc(bolus, time, iCfg.getPeak().toDouble(), iCfg.getDia())
    }

    private fun iobCalc(bolus: BS, time: Long, peak: Double, dia: Double): Iob {
        val result = Iob()
        if (bolus.amount != 0.0 && peak != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            val td = dia * 60 //getDIA() always >= MIN_DIA
            val tp = peak
            // force the IOB to 0 if over DIA hours have passed
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }

    @Synchronized
    override fun configuration(): JSONObject {
        val json = JSONObject()
        val jsonArray = JSONArray()
        insulins.forEach {
            try {
                jsonArray.put(it.toJson())
            } catch (e: Exception) {
                //
            }
        }
        json.put("insulins", jsonArray)
        return json
    }

    @Synchronized
    override fun applyConfiguration(configuration: JSONObject) {
        insulins.clear()
        configuration.optJSONArray("insulins")?.let {
            if (it.length() == 0) {   // new install
                addNewInsulin(InsulinType.OREF_RAPID_ACTING.getICfg())
            }
            for (index in 0 until (it.length())) {
                try {
                    val o = it.getJSONObject(index)
                    getOrCreateInsulin(ICfg.fromJson(o))
                } catch (e: Exception) {
                    //
                }
            }
        }
    }

    @Synchronized
    fun isValidEditState(activity: FragmentActivity?, verbose: Boolean = true): Boolean {
        with(currentInsulin) {
            if (getDia() < hardLimits.minDia() || getDia() > hardLimits.maxDia()) {
                if (verbose)
                    ToastUtils.errorToast(activity, rh.gs(app.aaps.core.ui.R.string.value_out_of_hard_limits, rh.gs(app.aaps.core.ui.R.string.insulin_dia), getDia()))
                return false
            }
            if (getPeak() < hardLimits.minPeak() || getPeak() > hardLimits.maxPeak()) {
                if (verbose)
                    ToastUtils.errorToast(activity, rh.gs(app.aaps.core.ui.R.string.value_out_of_hard_limits, rh.gs(app.aaps.core.ui.R.string.insulin_peak), getPeak()))
                return false
            }
            if (insulinLabel.isEmpty()) {
                if (verbose)
                    ToastUtils.errorToast(activity, rh.gs(R.string.missing_insulin_name))
                return false
            }
            // Check Inulin name is unique and insulin parameters is unique
            if (insulinLabelAlreadyExists(this.insulinLabel, currentInsulinIndex)) {
                if (verbose)
                    ToastUtils.errorToast(activity, rh.gs(R.string.insulin_name_exists, insulinLabel))
                return false
            }
        }
        return true
    }

    override fun isValid(testICfg: ICfg?): Boolean {
        if (testICfg == null)
            return false
        with(testICfg) {
            if (getDia() < hardLimits.minDia() || getDia() > hardLimits.maxDia())
                return false
            if (getPeak() < hardLimits.minPeak() || getPeak() > hardLimits.maxPeak())
                return false
        }
        return true
    }

    fun currentInsulin(): ICfg = insulins[currentInsulinIndex]

}