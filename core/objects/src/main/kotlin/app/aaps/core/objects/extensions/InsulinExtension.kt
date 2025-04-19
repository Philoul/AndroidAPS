package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.insulin.Insulin
import org.json.JSONObject

fun ICfg.toJson(): JSONObject = JSONObject()
    .put("insulinLabel", insulinLabel)
    .put("insulinEndTime", insulinEndTime)
    .put("insulinPeakTime", insulinPeakTime)
    .put("insulinTemplate", insulinTemplate)

fun ICfg.getTemplate() = if (insulinTemplate != 0) Insulin.InsulinType.fromInt(insulinTemplate) else Insulin.InsulinType.fromPeak(insulinPeakTime)

fun ICfg.setTemplate(template: Insulin.InsulinType) {
    insulinTemplate = template.value
}

fun ICfg.Companion.fromJson(json: JSONObject?): ICfg = ICfg(
    insulinLabel = json?.optString("insulinLabel", "") ?: "",
    insulinEndTime = json?.optLong("insulinEndTime", 0) ?: 0L,
    insulinPeakTime = json?.optLong("insulinPeakTime", 0) ?: 0L,
    insulinTemplate = json?.optInt("insulinTemplate", 0) ?: 0
)