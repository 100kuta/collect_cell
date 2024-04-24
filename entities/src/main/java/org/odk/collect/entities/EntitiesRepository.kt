package org.odk.collect.entities

interface EntitiesRepository {
    fun save(entity: Entity)
    fun getDatasets(): Set<String>
    fun getEntities(dataset: String): List<Entity>
    fun clear()
    fun addDataset(dataset: String)
    fun get(dataset: String, id: String): Entity?
}
