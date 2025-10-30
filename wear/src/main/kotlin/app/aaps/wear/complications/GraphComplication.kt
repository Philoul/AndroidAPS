@file:Suppress("DEPRECATION")

package app.aaps.wear.complications

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.support.wearable.complications.ComplicationData
import android.util.DisplayMetrics
import android.view.WindowManager
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.data.RawDisplayData
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.io.IOException

/*
 * Created by dlvoy on 2019-11-12
 */
class GraphComplication : BaseComplicationProviderService() {

    val wallpaperAssetsFileName: String = "watch_dark.jpg"
    private var disposable = CompositeDisposable()

    override fun getProviderCanonicalName(): String = GraphComplication::class.java.canonicalName!!
    override fun getComplicationAction(): ComplicationAction = ComplicationAction.NONE
    override fun buildComplicationData(dataType: Int, raw: RawDisplayData, complicationPendingIntent: PendingIntent): ComplicationData? {
        var complicationData: ComplicationData? = null
        // you can access to all bg values with raw.graphData.entries
        // There is a class to build watchfaces graphs (added value is linked to AAPS preferences so if you could re-use it it could be fine...
        // BgGraphBuilder(
        //                         sp, dateUtil, graphData.entries, treatmentData.predictions, treatmentData.temps, treatmentData.basals, treatmentData.boluses,
        //                         pointSize, highColor, lowColor, midColor, gridColor, basalBackgroundColor, basalCenterColor, bolusColor, carbColor, timeframe
        //                     )
        if (dataType == ComplicationData.TYPE_SMALL_IMAGE) {
            val metrics = DisplayMetrics()
            val windowManager = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val builder = ComplicationData.Builder(ComplicationData.TYPE_LARGE_IMAGE)
            val assetManager = assets
            try {
                assetManager.open(wallpaperAssetsFileName).use { iStr ->
                    val bitmap = BitmapFactory.decodeStream(iStr)
                    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    builder.setLargeImage(Icon.createWithBitmap(scaled))
                }
            } catch (e: IOException) {
                aapsLogger.error(LTag.WEAR, "Cannot read wallpaper asset: " + e.message, e)
            }
            complicationData = builder.build()
        } else {
            aapsLogger.warn(LTag.WEAR, "Unexpected complication type $dataType")
        }
        return complicationData
    }
}