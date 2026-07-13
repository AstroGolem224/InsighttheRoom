package itr.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import itr.app.ScanRepositoryStore
import itr.app.ScanStore
import itr.app.SettingsRepository
import itr.app.SettingsSource
import itr.persistence.ItrDatabase
import itr.persistence.ScanDao
import itr.persistence.ScanRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ItrDatabase =
        Room.databaseBuilder(context, ItrDatabase::class.java, "itr.db").build()

    @Provides
    fun scanDao(database: ItrDatabase): ScanDao = database.scanDao()

    @Provides
    @Singleton
    fun scanRepository(dao: ScanDao): ScanRepository = ScanRepository(dao)

    @Provides
    @Singleton
    fun scanStore(repository: ScanRepository): ScanStore = ScanRepositoryStore(repository)

    @Provides
    @Singleton
    fun settingsSource(repository: SettingsRepository): SettingsSource = repository
}
