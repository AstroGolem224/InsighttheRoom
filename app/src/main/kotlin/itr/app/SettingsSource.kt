package itr.app

import itr.core.render.Units

interface SettingsSource {
    suspend fun units(): Units
}
