package com.github.k1rakishou.model.repository

import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.model.KurobaDatabase
import com.github.k1rakishou.model.data.catalog.CompositeCatalog
import com.github.k1rakishou.model.source.local.CompositeCatalogLocalSource
import kotlinx.coroutines.CoroutineScope

class CompositeCatalogRepository(
  database: KurobaDatabase,
  private val applicationScope: CoroutineScope,
  private val localSource: CompositeCatalogLocalSource
) : AbstractRepository(database) {

  suspend fun maxOrder(): ModularResult<Int> {
    return applicationScope.dbCall {
      return@dbCall tryWithTransaction {
        return@tryWithTransaction localSource.maxOrder()
      }
    }
  }

  suspend fun loadAll(): ModularResult<List<CompositeCatalog>> {
    return applicationScope.dbCall {
      return@dbCall tryWithTransaction {
        return@tryWithTransaction localSource.loadAll()
      }
    }
  }

  suspend fun createOrUpdate(compositeCatalog: CompositeCatalog, order: Int): ModularResult<Unit> {
    return applicationScope.dbCall {
      return@dbCall tryWithTransaction {
        return@tryWithTransaction localSource.createOrUpdate(compositeCatalog, order)
      }
    }
  }

  suspend fun move(
    fromCompositeCatalog: CompositeCatalog,
    toCompositeCatalog: CompositeCatalog
  ): ModularResult<Boolean> {
    return applicationScope.dbCall {
      return@dbCall tryWithTransaction {
        return@tryWithTransaction localSource.move(fromCompositeCatalog, toCompositeCatalog)
      }
    }
  }

  suspend fun delete(compositeCatalog: CompositeCatalog): ModularResult<Unit> {
    return applicationScope.dbCall {
      return@dbCall tryWithTransaction {
        return@tryWithTransaction localSource.delete(compositeCatalog)
      }
    }
  }

}