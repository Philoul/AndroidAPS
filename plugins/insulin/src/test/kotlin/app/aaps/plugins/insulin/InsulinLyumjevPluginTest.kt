package app.aaps.plugins.insulin

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.shared.tests.TestBase
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.kotlin.whenever

class InsulinLyumjevPluginTest : TestBase() {

    private lateinit var sut: InsulinLyumjevPlugin

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var context: Context

    @BeforeEach
    fun setup() {
        sut = InsulinLyumjevPlugin(rh, preferences, aapsSchedulers, fabricPrivacy, persistenceLayer, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction, context)
    }

    @Test
    fun `simple peak test`() {
        assertThat(sut.peak).isEqualTo(45)
    }

    @Test
    fun getIdTest() {
        assertThat(sut.id).isEqualTo(Insulin.InsulinType.OREF_LYUMJEV)
    }

    @Test
    fun commentStandardTextTest() {
        whenever(rh.gs(eq(R.string.lyumjev))).thenReturn("Lyumjev")
        assertThat(sut.commentStandardText()).isEqualTo("Lyumjev")
    }

    @Test
    fun getFriendlyNameTest() {
        whenever(rh.gs(eq(R.string.lyumjev))).thenReturn("Lyumjev")
        assertThat(sut.friendlyName).isEqualTo("Lyumjev")
    }

}
