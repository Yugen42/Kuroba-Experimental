package com.github.k1rakishou.chan.features.bookmarks.watcher

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.core.manager.BookmarksManager
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.core_logger.Logger
import io.reactivex.Flowable
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BookmarkWatcherCoordinator(
  private val verboseLogsEnabled: Boolean,
  private val appContext: Context,
  private val appScope: CoroutineScope,
  private val appConstants: AppConstants,
  private val bookmarksManager: BookmarksManager,
  private val bookmarkForegroundWatcher: BookmarkForegroundWatcher
) {
  // We need this subject for events buffering
  private val bookmarkChangeSubject = PublishProcessor.create<SimpleBookmarkChangeInfo>()
  private val running = AtomicBoolean(false)

  fun initialize() {
    Logger.d(TAG, "BookmarkWatcherCoordinator.initialize()")

    appScope.launch {
      bookmarkChangeSubject
        .onBackpressureLatest()
        .buffer(1, TimeUnit.SECONDS)
        .onBackpressureLatest()
        .filter { events -> events.isNotEmpty() }
        .asFlow()
        .collect { groupOfChangeInfos ->
          bookmarksManager.awaitUntilInitialized()

          val hasCreateBookmarkChange = groupOfChangeInfos
            .any { simpleBookmarkChangeInfo -> simpleBookmarkChangeInfo.hasNewlyCreatedBookmarkChange }

          onBookmarksChanged(hasCreateBookmarkChange = hasCreateBookmarkChange)
        }
    }

    appScope.launch {
      bookmarksManager.listenForBookmarksChanges()
        .buffer(1, TimeUnit.SECONDS)
        .filter { groupOfChanges -> groupOfChanges.isNotEmpty() }
        .asFlow()
        // Pass the filter if we have at least one bookmark change that we actually want
        .filter { groupOfChanges -> groupOfChanges.any { change -> isWantedBookmarkChange(change) } }
        .collect { groupOfChanges ->
          if (verboseLogsEnabled) {
            Logger.d(TAG, "Calling onBookmarksChanged() because bookmarks have actually changed")
          }

          val hasCreateBookmarkChange = groupOfChanges
            .any { change -> change is BookmarksManager.BookmarkChange.BookmarksCreated }

          val simpleBookmarkChangeInfo = SimpleBookmarkChangeInfo(hasCreateBookmarkChange)
          bookmarkChangeSubject.onNext(simpleBookmarkChangeInfo)
        }
    }

    appScope.launch {
      val watchEnabledFlowable = ChanSettings.watchEnabled.listenForChanges()
        .map { enabled -> WatchSettingChange.WatcherSettingChanged(enabled) }
        .distinctUntilChanged()
      val watchBackgroundFlowable = ChanSettings.watchBackground.listenForChanges()
        .map { enabled -> WatchSettingChange.BackgroundWatcherSettingChanged(enabled) }
        .distinctUntilChanged()
      val watchBackgroundIntervalFlowable = ChanSettings.watchBackgroundInterval.listenForChanges()
        .map { interval -> WatchSettingChange.BackgroundWatcherIntervalSettingChanged(interval) }
        .distinctUntilChanged()

      Flowable.merge(watchEnabledFlowable, watchBackgroundFlowable, watchBackgroundIntervalFlowable)
        .asFlow()
        .collect { watchSettingChange ->
          if (verboseLogsEnabled) {
            when (watchSettingChange) {
              is WatchSettingChange.WatcherSettingChanged -> {
                Logger.d(TAG, "Calling onBookmarksChanged() watchEnabled setting changed")
              }
              is WatchSettingChange.BackgroundWatcherSettingChanged -> {
                Logger.d(TAG, "Calling onBookmarksChanged() watchBackground setting changed")
              }
              is WatchSettingChange.BackgroundWatcherIntervalSettingChanged -> {
                Logger.d(TAG, "Calling onBookmarksChanged() watchBackgroundInterval setting changed")
              }
            }
          }

          val simpleBookmarkChangeInfo = SimpleBookmarkChangeInfo(
            hasNewlyCreatedBookmarkChange = false
          )

          bookmarkChangeSubject.onNext(simpleBookmarkChangeInfo)
        }
    }
  }

  @Synchronized
  private suspend fun onBookmarksChanged(hasCreateBookmarkChange: Boolean = false) {
    if (!running.compareAndSet(false, true)) {
      return
    }

    try {
      val hasActiveBookmarks = bookmarksManager.hasActiveBookmarks()
      if (!hasActiveBookmarks) {
        Logger.d(TAG, "onBookmarksChanged() no active bookmarks, nothing to do")

        cancelForegroundBookmarkWatching()
        cancelBackgroundBookmarkWatching(appConstants, appContext)
        return
      }

      if (!ChanSettings.watchEnabled.get()) {
        Logger.d(TAG, "onBookmarksChanged() watchEnabled is false, stopping foreground watcher")

        cancelForegroundBookmarkWatching()
        cancelBackgroundBookmarkWatching(appConstants, appContext)
        return
      }

      if (!ChanSettings.watchBackground.get()) {
        Logger.d(TAG, "onBookmarksChanged() watchBackground is false, stopping background watcher")
        cancelBackgroundBookmarkWatching(appConstants, appContext)

        // fallthrough because we need to update the foreground watcher
      }

      if (hasCreateBookmarkChange) {
        Logger.d(TAG, "onBookmarksChanged() hasCreateBookmarkChange==true, restarting the foreground watcher")
        bookmarkForegroundWatcher.restartWatching()
        return
      }

      Logger.d(TAG, "onBookmarksChanged() calling startWatchingIfNotWatchingYet()")
      bookmarkForegroundWatcher.startWatchingIfNotWatchingYet()
    } finally {
      running.set(false)
    }
  }

  private fun cancelForegroundBookmarkWatching() {
    Logger.d(TAG, "cancelForegroundBookmarkWatching() called")
    bookmarkForegroundWatcher.stopWatching()
  }

  private fun isWantedBookmarkChange(bookmarkChange: BookmarksManager.BookmarkChange): Boolean {
    return when (bookmarkChange) {
      BookmarksManager.BookmarkChange.BookmarksInitialized,
      is BookmarksManager.BookmarkChange.BookmarksCreated,
      is BookmarksManager.BookmarkChange.BookmarksDeleted -> true
      is BookmarksManager.BookmarkChange.BookmarksUpdated -> false
    }
  }

  private data class SimpleBookmarkChangeInfo(
    val hasNewlyCreatedBookmarkChange: Boolean
  )

  private sealed class WatchSettingChange {
    data class WatcherSettingChanged(val enabled: Boolean) : WatchSettingChange()
    data class BackgroundWatcherSettingChanged(val enabled: Boolean) : WatchSettingChange()
    data class BackgroundWatcherIntervalSettingChanged(val interval: Int) : WatchSettingChange()
  }

  companion object {
    private const val TAG = "BookmarkWatcherController"

    suspend fun restartBackgroundWork(appConstants: AppConstants, appContext: Context) {
      val tag = appConstants.bookmarkWatchWorkUniqueTag
      Logger.d(TAG, "restartBackgroundJob() called tag=$tag")

      if (!ChanSettings.watchBackground.get()) {
        cancelBackgroundBookmarkWatching(appConstants, appContext)
        return
      }

      val backgroundIntervalMillis = ChanSettings.watchBackgroundInterval.get().toLong()

      val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

      val workRequest = OneTimeWorkRequestBuilder<BookmarkBackgroundWatcherWorker>()
        .addTag(tag)
        .setInitialDelay(backgroundIntervalMillis, TimeUnit.MILLISECONDS)
        .setConstraints(constraints)
        .build()

      WorkManager
        .getInstance(appContext)
        .enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, workRequest)
        .result
        .await()

      Logger.d(TAG, "restartBackgroundJob() enqueued work with tag $tag")
    }

    suspend fun cancelBackgroundBookmarkWatching(
      appConstants: AppConstants,
      appContext: Context
    ) {
      val tag = appConstants.bookmarkWatchWorkUniqueTag
      Logger.d(TAG, "cancelBackgroundBookmarkWatching() called tag=$tag")

      WorkManager
        .getInstance(appContext)
        .cancelUniqueWork(tag)
        .result
        .await()

      Logger.d(TAG, "cancelBackgroundBookmarkWatching() work with tag $tag canceled")
    }
  }
}