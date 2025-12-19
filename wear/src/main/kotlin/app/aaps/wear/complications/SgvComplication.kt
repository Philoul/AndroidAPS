package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import app.aaps.core.interfaces.logging.LTag
import dagger.android.AndroidInjection
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * SGV (Sensor Glucose Value) Complication
 *
 * Shows current blood glucose with arrow and delta/time
 *
 */
class SgvComplication : ModernBaseComplicationProviderService() {

    // Not derived from DaggerService, do injection here
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        // Use dataset 0 (primary)
        val bgData = data.bgData
        aapsLogger.debug(LTag.WEAR, "SgvComplication building: dataset=0 sgv=${bgData.sgvString} arrow=${bgData.slopeArrow}")

        return when (type) {
            ComplicationType.SHORT_TEXT      -> {
                val shortText = bgData.sgvString + bgData.slopeArrow + "\uFE0E"

                val shortTitle = TimeDifferenceComplicationText.Builder(
                    style = TimeDifferenceStyle.STOPWATCH,
                    countUpTimeReference = CountUpTimeReference(Instant.ofEpochMilli(bgData.timeStamp))
                )
                    .setMinimumTimeUnit(TimeUnit.MINUTES)
                    .build()

                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = shortText).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose $shortText").build()
                )
                    .setTitle(shortTitle)
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            else                             -> {
                aapsLogger.warn(LTag.WEAR, "SgvComplication unexpected type: $type")
                null
            }
        }
    }

    override fun getProviderCanonicalName(): String = SgvComplication::class.java.canonicalName!!
}