package info.chrzanowski.ideatmosphere.listeners

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import info.chrzanowski.ideatmosphere.AtmosphereManager

/** Recomputes the effect palette whenever the Look and Feel (theme) changes. */
class AtmosphereLafListener : LafManagerListener {

    override fun lookAndFeelChanged(source: LafManager) {
        AtmosphereManager.getInstance().refreshPalette()
    }
}
