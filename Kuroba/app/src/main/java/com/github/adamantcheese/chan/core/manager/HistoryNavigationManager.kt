package com.github.adamantcheese.chan.core.manager

import com.github.adamantcheese.chan.core.base.SerializedCoroutineExecutor
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.common.ModularResult
import com.github.adamantcheese.common.SuspendableInitializer
import com.github.adamantcheese.common.mutableListWithCap
import com.github.adamantcheese.model.data.descriptor.ChanDescriptor
import com.github.adamantcheese.model.data.navigation.NavHistoryElement
import com.github.adamantcheese.model.data.navigation.NavHistoryElementInfo
import com.github.adamantcheese.model.repository.HistoryNavigationRepository
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HistoryNavigationManager(
  private val appScope: CoroutineScope,
  private val historyNavigationRepository: HistoryNavigationRepository,
  private val applicationVisibilityManager: ApplicationVisibilityManager
) {
  private val navigationStackChangesSubject = PublishProcessor.create<Unit>()
  private val persistTaskSubject = PublishProcessor.create<Unit>()
  private val serializedCoroutineExecutor = SerializedCoroutineExecutor(appScope)
  private val persistRunning = AtomicBoolean(false)

  private val navigationStack = mutableListWithCap<NavHistoryElement>(64)
  private val suspendableInitializer = SuspendableInitializer<Unit>("HistoryNavigationManager")

  init {
    appScope.launch {
      suspendableInitializer.awaitUntilInitialized()

      applicationVisibilityManager.listenForAppVisibilityUpdates()
        .asFlow()
        .filter { visibility -> visibility == ApplicationVisibility.Background }
        .collect { persisNavigationStack(true) }
    }

    appScope.launch {
      persistTaskSubject
        .debounce(1, TimeUnit.SECONDS)
        .collect {
          persisNavigationStack()
        }
    }

    appScope.launch {
      @Suppress("MoveVariableDeclarationIntoWhen")
      val loadedNavElementsResult = historyNavigationRepository.initialize(MAX_NAV_HISTORY_ENTRIES)
      when (loadedNavElementsResult) {
        is ModularResult.Value -> {
          BackgroundUtils.ensureMainThread()

          navigationStack.addAll(loadedNavElementsResult.value)
          suspendableInitializer.initWithValue(Unit)

          Logger.d(TAG, "HistoryNavigationManager initialized!")
        }
        is ModularResult.Error -> {
          Logger.e(TAG, "Exception while initializing HistoryNavigationManager", loadedNavElementsResult.error)
          suspendableInitializer.initWithError(loadedNavElementsResult.error)
        }
      }

      navStackChanged()
    }
  }

  fun runAfterInitialized(func: (Throwable?) -> Unit) {
    suspendableInitializer.invokeAfterInitialized(func)
  }

  fun getAll(): List<NavHistoryElement> {
    BackgroundUtils.ensureMainThread()

    return navigationStack.toList()
  }

  fun getNavElementAtTop(): NavHistoryElement? {
    return navigationStack.firstOrNull()
  }

  fun getFirstCatalogNavElement(): NavHistoryElement? {
    return navigationStack.firstOrNull { navHistoryElement ->
      navHistoryElement is NavHistoryElement.Catalog
    }
  }

  fun listenForNavigationStackChanges(): Flowable<Unit> {
    return navigationStackChangesSubject
      .observeOn(AndroidSchedulers.mainThread())
      .hide()
  }

  suspend fun awaitUntilInitialized() = suspendableInitializer.awaitUntilInitialized()

  fun createNewNavElement(
    descriptor: ChanDescriptor,
    thumbnailImageUrl: HttpUrl,
    title: String
  ) {
    serializedCoroutineExecutor.post {
      BackgroundUtils.ensureMainThread()

      val navElementInfo = NavHistoryElementInfo(thumbnailImageUrl, title)
      val navElement = when (descriptor) {
        is ChanDescriptor.ThreadDescriptor -> NavHistoryElement.Thread(descriptor, navElementInfo)
        is ChanDescriptor.CatalogDescriptor -> NavHistoryElement.Catalog(descriptor, navElementInfo)
      }

      if (!addNewOrIgnore(navElement)) {
        return@post
      }

      navStackChanged()
    }
  }

  fun moveNavElementToTop(descriptor: ChanDescriptor) {
    serializedCoroutineExecutor.post {
      BackgroundUtils.ensureMainThread()

      val indexOfElem = navigationStack.indexOfFirst { navHistoryElement ->
        return@indexOfFirst when (navHistoryElement) {
          is NavHistoryElement.Catalog -> navHistoryElement.descriptor == descriptor
          is NavHistoryElement.Thread -> navHistoryElement.descriptor == descriptor
        }
      }

      if (indexOfElem < 0) {
        return@post
      }

      // Move the existing navigation element at the top of the list
      navigationStack.add(0, navigationStack.removeAt(indexOfElem))
      navStackChanged()
    }
  }

  fun onNavElementRemoved(descriptor: ChanDescriptor) {
    serializedCoroutineExecutor.post {
      BackgroundUtils.ensureMainThread()

      val indexOfElem = navigationStack.indexOfFirst { navHistoryElement ->
        return@indexOfFirst when (navHistoryElement) {
          is NavHistoryElement.Catalog -> navHistoryElement.descriptor == descriptor
          is NavHistoryElement.Thread -> navHistoryElement.descriptor == descriptor
        }
      }

      if (indexOfElem < 0) {
        return@post
      }

      navigationStack.removeAt(indexOfElem)
      navStackChanged()
    }
  }

  fun isAtTop(descriptor: ChanDescriptor): Boolean {
    BackgroundUtils.ensureMainThread()

    val topNavElement = navigationStack.firstOrNull()
      ?: return false

    val topNavElementDescriptor = when (topNavElement) {
      is NavHistoryElement.Catalog -> topNavElement.descriptor
      is NavHistoryElement.Thread -> topNavElement.descriptor
    }

    return topNavElementDescriptor == descriptor
  }

  private fun persisNavigationStack(blocking: Boolean = false) {
    BackgroundUtils.ensureMainThread()

    if (!persistRunning.compareAndSet(false, true)) {
      return
    }

    if (blocking) {
      runBlocking {
        Logger.d(TAG, "persistNavigationStack blocking called")

        try {
          historyNavigationRepository.persist(navigationStack.toList())
            .safeUnwrap { error ->
              Logger.e(TAG, "Error while trying to persist navigation stack", error)
              return@runBlocking
            }
        } finally {
          Logger.d(TAG, "persistNavigationStack blocking finished")
          persistRunning.set(false)
        }
      }
    } else {
      serializedCoroutineExecutor.post {
        Logger.d(TAG, "persistNavigationStack async called")

        try {
          historyNavigationRepository.persist(navigationStack.toList())
            .safeUnwrap { error ->
              Logger.e(TAG, "Error while trying to persist navigation stack", error)
              return@post
            }
        } finally {
          Logger.d(TAG, "persistNavigationStack async finished")
          persistRunning.set(false)
        }
      }
    }
  }

  private fun addNewOrIgnore(navElement: NavHistoryElement): Boolean {
    BackgroundUtils.ensureMainThread()

    val indexOfElem = navigationStack.indexOf(navElement)
    if (indexOfElem >= 0) {
      return false
    }

    navigationStack.add(0, navElement)
    return true
  }

  private fun navStackChanged() {
    navigationStackChangesSubject.onNext(Unit)
    persistTaskSubject.onNext(Unit)
  }

  companion object {
    private const val TAG = "HistoryNavigationManager"
    private const val MAX_NAV_HISTORY_ENTRIES = 64
  }
}