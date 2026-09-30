package info.chrzanowski.ideatmosphere.listeners

import com.intellij.ide.PowerSaveMode
import info.chrzanowski.ideatmosphere.AtmosphereManager

/** Freezes the animation while Power Save mode is on and resumes it when it is switched off. */
class AtmospherePowerSaveListener : PowerSaveMode.Listener {

    override fun powerSaveStateChanged() {
        AtmosphereManager.getInstance().setPaused(AtmosphereManager.PauseReason.POWER_SAVE, PowerSaveMode.isEnabled())
    }
}
