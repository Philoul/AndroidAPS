package app.aaps.plugins.insulin

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InsulinLyumjevPlugin @Inject constructor(
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction
) : InsulinOrefBasePlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_LYUMJEV
    override val friendlyName get(): String = rh.gs(app.aaps.core.interfaces.R.string.lyumjev)
    override fun getOrCreateInsulin(iCfg: ICfg) = ICfg(rh.gs(app.aaps.core.interfaces.R.string.lyumjev), peak, dia)
    override fun getInsulin(insulinLabel: String)= ICfg(rh.gs(app.aaps.core.interfaces.R.string.lyumjev), peak, dia)

    override fun configuration(): JSONObject = JSONObject()
    override fun applyConfiguration(configuration: JSONObject) { }

    override fun commentStandardText(): String = rh.gs(app.aaps.core.interfaces.R.string.lyumjev)

    override val peak = 45

    init {
        pluginDescription
            .pluginIcon(app.aaps.core.objects.R.drawable.ic_insulin)
            .pluginName(app.aaps.core.interfaces.R.string.lyumjev)
            .description(R.string.description_insulin_lyumjev)
    }
}