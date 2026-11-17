package app.aaps.ui.compose.foodManagement

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.foodManagement.images.decodeFoodImage

/**
 * The photo of a food, or nothing when there is none (a food typed by hand, a product without a
 * photo, or a photo still downloading). The name is always written next to it, so the photo has no
 * text for screen readers.
 */
@Composable
internal fun FoodPhoto(bytes: ByteArray?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { bytes?.let { decodeFoodImage(it) } } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
    )
}

/**
 * The photo at the top of the food editor, with the buttons to take a photo or to remove it.
 *
 * @param shown the photo shown, or null
 * @param canRemove true when [shown] is a photo of the user (an OFF photo cannot be removed)
 */
@Composable
internal fun ColumnScope.UserPhotoSection(
    shown: ByteArray?,
    canRemove: Boolean,
    showCamera: Boolean,
    onOpenCamera: () -> Unit,
    onCloseCamera: () -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onRemove: () -> Unit,
) {
    shown?.let { FoodPhoto(bytes = it, size = AapsSpacing.foodPhotoSize, modifier = Modifier.align(Alignment.CenterHorizontally)) }
    if (!foodPhotoCameraAvailable) return
    if (showCamera) {
        FoodPhotoCamera(
            onPhoto = onPhoto,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        )
        TextButton(onClick = onCloseCamera, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(CoreUiStrings.cancel))
        }
    } else Row(modifier = Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        TextButton(onClick = onOpenCamera) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(AapsSpacing.small))
            Text(stringResource(UiStrings.food_photo_take))
        }
        if (canRemove) TextButton(onClick = onRemove) {
            Text(stringResource(UiStrings.food_photo_remove))
        }
    }
}
