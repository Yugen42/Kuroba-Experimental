package com.github.k1rakishou.chan.core.usecase

import com.github.k1rakishou.chan.core.base.okhttp.ProxiedOkHttpClient
import com.github.k1rakishou.chan.core.helper.FilterEngine
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.BookmarksManager
import com.github.k1rakishou.chan.core.manager.ChanFilterManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.site.parser.ChanReader
import com.github.k1rakishou.chan.core.site.parser.search.SimpleCommentParser
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.common.suspendCall
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.filter.ChanFilter
import com.github.k1rakishou.model.data.filter.FilterType
import com.github.k1rakishou.model.data.filter.FilterWatchCatalogInfoObject
import com.github.k1rakishou.model.data.filter.FilterWatchCatalogThreadInfoObject
import com.github.k1rakishou.model.repository.ChanPostRepository
import com.github.k1rakishou.model.util.ChanPostUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.parser.Parser
import java.io.IOException

class BookmarkFilterWatchableThreadsUseCase(
  private val verboseLogsEnabled: Boolean,
  private val appConstants: AppConstants,
  private val boardManager: BoardManager,
  private val bookmarksManager: BookmarksManager,
  private val chanFilterManager: ChanFilterManager,
  private val siteManager: SiteManager,
  private val appScope: CoroutineScope,
  private val proxiedOkHttpClient: ProxiedOkHttpClient,
  private val simpleCommentParser: SimpleCommentParser,
  private val filterEngine: FilterEngine,
  private val chanPostRepository: ChanPostRepository
) : ISuspendUseCase<Unit, ModularResult<Unit>> {

  override suspend fun execute(parameter: Unit): ModularResult<Unit> {
    return ModularResult.Try { executeInternal() }
  }

  @Suppress("UnnecessaryVariable")
  private suspend fun executeInternal() {
    check(boardManager.isReady()) { "boardManager is not ready" }
    check(bookmarksManager.isReady()) { "bookmarksManager is not ready" }
    check(chanFilterManager.isReady()) { "chanFilterManager is not ready" }
    check(siteManager.isReady()) { "siteManager is not ready" }

    val boardDescriptorsToCheck = collectBoardDescriptorsToCheck()
    if (boardDescriptorsToCheck.isEmpty()) {
      Logger.d(TAG, "doWorkInternal() boardDescriptorsToCheck is empty")
      return
    }

    val enabledWatchFilters = chanFilterManager.getEnabledWatchFilters()
    if (enabledWatchFilters.isEmpty()) {
      Logger.d(TAG, "doWorkInternal() enabledWatchFilters is empty")
      return
    }

    if (verboseLogsEnabled) {
      enabledWatchFilters.forEach { watchFilter ->
        Logger.d(TAG, "doWorkInternal() watchFilter=$watchFilter")
      }
    }

    val catalogFetchResults = fetchFilterWatcherCatalogs(
      boardDescriptorsToCheck,
    )

    val filterWatchCatalogInfoObject = filterOutNonSuccessResults(catalogFetchResults)
    if (filterWatchCatalogInfoObject.isEmpty()) {
      Logger.d(TAG, "doWorkInternal() Nothing has left after filtering out error results")
      return
    }

    val matchedCatalogThreads = filterOutThreadsThatDoNotMatchWatchFilters(
      filterWatchCatalogInfoObject
    ) { catalogThread ->
      val rawComment = catalogThread.comment()
      val subject = catalogThread.subject
      val parsedComment = simpleCommentParser.parseComment(rawComment) ?: ""

      // Updated the old unparsed comment with the parsed one
      catalogThread.replaceRawCommentWithParsed(parsedComment.toString())

      val matches = tryMatchWatchFiltersWithThreadInfo(
        enabledWatchFilters,
        parsedComment,
        subject
      )

      return@filterOutThreadsThatDoNotMatchWatchFilters matches
    }

    if (matchedCatalogThreads.isEmpty()) {
      Logger.d(TAG, "doWorkInternal() Nothing has left after filtering out non-matching catalog threads")
      return
    }

    createBookmarks(matchedCatalogThreads)
    Logger.d(TAG, "doWorkInternal() Success")
  }

  private suspend fun createBookmarks(matchedCatalogThreads: List<FilterWatchCatalogThreadInfoObject>) {
    val bookmarksToCreate = mutableListOf<BookmarksManager.SimpleThreadBookmark>()
    val bookmarksToUpdate = mutableListOf<ChanDescriptor.ThreadDescriptor>()

    matchedCatalogThreads.forEach { filterWatchCatalogThreadInfoObject ->
      val threadDescriptor = filterWatchCatalogThreadInfoObject.threadDescriptor

      val isFilterWatchBookmark = bookmarksManager.mapBookmark(threadDescriptor) { threadBookmarkView ->
        return@mapBookmark threadBookmarkView.isFilterWatchBookmark()
      }

      if (isFilterWatchBookmark == null) {
        // No such bookmark exists
        bookmarksToCreate += BookmarksManager.SimpleThreadBookmark(
          threadDescriptor = threadDescriptor,
          title = createBookmarkSubject(filterWatchCatalogThreadInfoObject),
          thumbnailUrl = filterWatchCatalogThreadInfoObject.thumbnailUrl
        )

        return@forEach
      }

      if (!isFilterWatchBookmark) {
        // Bookmark exists but has no "Filter watch" flag
        return@forEach
      }

      // Bookmark already created and has "Filter watch" flag
    }

    if (bookmarksToCreate.isEmpty() && bookmarksToUpdate.isEmpty()) {
      Logger.d(TAG, "createBookmarks() nothing to create, nothing to update")
      return
    }

    if (bookmarksToCreate.isNotEmpty()) {
      val createdThreadBookmarks = bookmarksToCreate.mapNotNull { simpleThreadBookmark ->
        val success = chanPostRepository.createEmptyThreadIfNotExists(simpleThreadBookmark.threadDescriptor)
          .peekError { error ->
            Logger.e(TAG, "createEmptyThreadIfNotExists() " +
              "threadDescriptor=${simpleThreadBookmark.threadDescriptor} error", error)
          }
          .valueOrNull() == true

        if (!success) {
          return@mapNotNull null
        }

        return@mapNotNull simpleThreadBookmark
      }

      bookmarksManager.createBookmarks(createdThreadBookmarks)

      Logger.d(TAG, "createBookmarks() created ${createdThreadBookmarks.size} " +
        "out of ${bookmarksToCreate.size} bookmarks")
    }

    if (bookmarksToUpdate.isNotEmpty()) {
      bookmarksManager.updateBookmarks(bookmarksToUpdate) { threadBookmark ->
        threadBookmark.setFilterWatchFlag()
      }

      Logger.d(TAG, "createBookmarks() updated ${bookmarksToUpdate.size} bookmarks")
    }
  }

  private fun createBookmarkSubject(
    filterWatchCatalogThreadInfoObject: FilterWatchCatalogThreadInfoObject
  ): String {
    val subject = Parser.unescapeEntities(filterWatchCatalogThreadInfoObject.subject, false)
    val comment = filterWatchCatalogThreadInfoObject.comment()
    val threadDescriptor = filterWatchCatalogThreadInfoObject.threadDescriptor

    return ChanPostUtils.getTitle(subject, comment, threadDescriptor)
  }

  private fun tryMatchWatchFiltersWithThreadInfo(
    enabledWatchFilters: List<ChanFilter>,
    parsedComment: CharSequence,
    subject: String
  ): Boolean {
    for (watchFilter in enabledWatchFilters) {
      if (filterEngine.typeMatches(watchFilter, FilterType.COMMENT)) {
        if (filterEngine.matchesNoHtmlConversion(watchFilter, parsedComment, false)) {
          return true
        }
      }

      if (filterEngine.typeMatches(watchFilter, FilterType.SUBJECT)) {
        if (filterEngine.matchesNoHtmlConversion(watchFilter, subject, false)) {
          return true
        }
      }
    }

    return false
  }

  private suspend fun filterOutThreadsThatDoNotMatchWatchFilters(
    filterWatchCatalogInfoObjects: List<FilterWatchCatalogInfoObject>,
    predicate: suspend (FilterWatchCatalogThreadInfoObject) -> Boolean
  ): List<FilterWatchCatalogThreadInfoObject> {
    val batchSize = (appConstants.processorsCount * BATCH_PER_CORE)
      .coerceAtLeast(MIN_BATCHES_COUNT)

    return supervisorScope {
      return@supervisorScope filterWatchCatalogInfoObjects.flatMap { filterWatchCatalogInfoObject ->
        return@flatMap filterWatchCatalogInfoObject.catalogThreads
          .chunked(batchSize)
          .flatMap { chunk ->
            return@flatMap chunk.mapNotNull { catalogThread ->
              return@mapNotNull appScope.async(Dispatchers.IO) {
                try {
                  if (predicate(catalogThread)) {
                    return@async catalogThread
                  }
                } catch (error: Throwable) {
                  if (verboseLogsEnabled) {
                    Logger.e(TAG, "iterateResultsConcurrently error", error)
                  } else {
                    val errorMessage = error.errorMessageOrClassName()
                    Logger.e(TAG, "iterateResultsConcurrently error=${errorMessage}")
                  }
                }

                return@async null
              }
            }.awaitAll()
              .filterNotNull()
          }
      }
    }
  }

  private fun filterOutNonSuccessResults(
    catalogFetchResults: List<CatalogFetchResult>
  ): List<FilterWatchCatalogInfoObject> {
    return catalogFetchResults.mapNotNull { catalogFetchResult ->
      when (catalogFetchResult) {
        is CatalogFetchResult.Success -> {
          return@mapNotNull catalogFetchResult.filterWatchCatalogInfoObject
        }
        is CatalogFetchResult.Error -> {
          if (verboseLogsEnabled) {
            Logger.e(TAG, "catalogFetchResult failure", catalogFetchResult.error)
          } else {
            val errorMessage = catalogFetchResult.error.errorMessageOrClassName()
            Logger.e(TAG, "catalogFetchResult failure, error=${errorMessage}")
          }

          return@mapNotNull null
        }
      }
    }
  }

  private suspend fun fetchFilterWatcherCatalogs(
    boardDescriptorsToCheck: Set<BoardDescriptor>,
  ): List<CatalogFetchResult> {
    val batchSize = (appConstants.processorsCount * BATCH_PER_CORE)
      .coerceAtLeast(MIN_BATCHES_COUNT)

    return boardDescriptorsToCheck
      .chunked(batchSize)
      .flatMap { chunk ->
        return@flatMap supervisorScope {
          return@supervisorScope chunk.map { boardDescriptor ->
            return@map appScope.async(Dispatchers.IO) {
              val site = siteManager.bySiteDescriptor(boardDescriptor.siteDescriptor)
              if (site == null) {
                Logger.e(TAG, "Site with descriptor ${boardDescriptor.siteDescriptor} " +
                    "not found in siteRepository!")
                return@async null
              }

              val catalogJsonEndpoint = site.endpoints().catalog(boardDescriptor)

              return@async fetchBoardCatalog(
                boardDescriptor,
                catalogJsonEndpoint,
                site.chanReader()
              )
            }
          }
        }
          .awaitAll()
          .filterNotNull()
      }
  }

  private suspend fun fetchBoardCatalog(
    boardDescriptor: BoardDescriptor,
    catalogJsonEndpoint: HttpUrl,
    chanReader: ChanReader
  ): CatalogFetchResult {
    if (verboseLogsEnabled) {
      Logger.d(TAG, "fetchBoardCatalog() catalogJsonEndpoint=$catalogJsonEndpoint")
    }

    val request = Request.Builder()
      .url(catalogJsonEndpoint)
      .get()
      .build()

    val response = try {
      proxiedOkHttpClient.okHttpClient().suspendCall(request)
    } catch (exception: IOException) {
      val error = IOException("Failed to execute network request " +
        "error=${exception.errorMessageOrClassName()}, catalogJsonEndpoint=$catalogJsonEndpoint")

      return CatalogFetchResult.Error(error)
    }

    if (!response.isSuccessful) {
      val error = IOException("Bad status code: code=${response.code}, " +
        "catalogJsonEndpoint=$catalogJsonEndpoint")

      return CatalogFetchResult.Error(error)
    }

    val responseBody = response.body
    if (responseBody == null) {
      val error = IOException("Response has no body catalogJsonEndpoint=$catalogJsonEndpoint")
      return CatalogFetchResult.Error(error)
    }

    val filterWatchCatalogInfoObjectResult = chanReader.readFilterWatchCatalogInfoObject(
      boardDescriptor,
      request,
      responseBody
    )

    if (filterWatchCatalogInfoObjectResult is ModularResult.Error) {
      return CatalogFetchResult.Error(filterWatchCatalogInfoObjectResult.error)
    }

    filterWatchCatalogInfoObjectResult as ModularResult.Value

    return CatalogFetchResult.Success(filterWatchCatalogInfoObjectResult.value)
  }

  private fun collectBoardDescriptorsToCheck(): Set<BoardDescriptor> {
    val boardDescriptorsToCheck = mutableSetOf<BoardDescriptor>()

    chanFilterManager.viewAllFilters { chanFilter ->
      if (!chanFilter.isEnabledWatchFilter()) {
        return@viewAllFilters
      }

      if (chanFilter.allBoards()) {
        boardManager.viewAllActiveBoards { chanBoard ->
          boardDescriptorsToCheck += chanBoard.boardDescriptor
        }

        return@viewAllFilters
      }

      chanFilter.boards.forEach { boardDescriptor ->
        val isBoardActive = boardManager.byBoardDescriptor(boardDescriptor)?.active
          ?: false

        if (!isBoardActive) {
          return@forEach
        }

        boardDescriptorsToCheck += boardDescriptor
      }
    }

    return boardDescriptorsToCheck
  }

  sealed class CatalogFetchResult {
    data class Success(
      val filterWatchCatalogInfoObject: FilterWatchCatalogInfoObject
    ) : CatalogFetchResult()

    data class Error(val error: Throwable) : CatalogFetchResult()
  }

  companion object {
    private const val TAG = "BookmarkFilterWatchableThreadsUseCase"

    private const val BATCH_PER_CORE = 4
    private const val MIN_BATCHES_COUNT = 8
  }
}