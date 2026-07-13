package itr.app

import itr.core.model.Building

interface ScanStore {
    suspend fun list(): List<Building>
    suspend fun load(id: String): Building?
    suspend fun save(building: Building)
    suspend fun delete(id: String)
}
