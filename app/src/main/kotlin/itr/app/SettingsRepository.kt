package itr.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import itr.core.render.Units
import itr.core.settings.AppSettings
import itr.core.settings.unitsFromName
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsSource {
    val flow: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                units = unitsFromName(preferences[UNITS]),
                snapByDefault = preferences[SNAP] ?: true,
                diagnosticLog = preferences[DIAGNOSTIC] ?: false,
            )
        }

    suspend fun get(): AppSettings = flow.first()

    override suspend fun units(): Units = get().units

    suspend fun setUnits(units: Units) {
        dataStore.edit { it[UNITS] = units.name }
    }

    suspend fun setSnap(enabled: Boolean) {
        dataStore.edit { it[SNAP] = enabled }
    }

    suspend fun setDiagnosticLog(enabled: Boolean) {
        dataStore.edit { it[DIAGNOSTIC] = enabled }
    }

    private companion object {
        val UNITS = stringPreferencesKey("units")
        val SNAP = booleanPreferencesKey("snap")
        val DIAGNOSTIC = booleanPreferencesKey("diagnostic")
    }
}
