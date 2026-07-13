package itr.app

import itr.core.model.Building
import itr.persistence.ScanRepository

class ScanRepositoryStore(private val repo: ScanRepository) : ScanStore {
    override suspend fun list(): List<Building> = repo.listBuildings()
    override suspend fun load(id: String): Building? = repo.loadBuilding(id)
    override suspend fun save(building: Building) = repo.saveBuilding(building)
    override suspend fun delete(id: String) = repo.deleteBuilding(id)
}
