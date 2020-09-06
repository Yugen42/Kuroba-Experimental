package com.github.adamantcheese.chan.features.setup

import com.github.adamantcheese.chan.Chan
import com.github.adamantcheese.chan.core.base.BasePresenter
import com.github.adamantcheese.chan.core.manager.BoardManager
import com.github.adamantcheese.chan.core.manager.SiteManager
import com.github.adamantcheese.chan.features.setup.data.AddBoardsControllerState
import com.github.adamantcheese.chan.features.setup.data.BoardCellData
import com.github.adamantcheese.chan.features.setup.data.SelectableBoardCellData
import com.github.adamantcheese.chan.ui.helper.BoardDescriptorsComparator
import com.github.adamantcheese.chan.ui.helper.BoardHelper
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.common.errorMessageOrClassName
import com.github.adamantcheese.common.mutableListWithCap
import com.github.adamantcheese.model.data.descriptor.BoardDescriptor
import com.github.adamantcheese.model.data.descriptor.SiteDescriptor
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class AddBoardsPresenter(
  private val siteDescriptor: SiteDescriptor
) : BasePresenter<AddBoardsView>() {
  private val stateSubject = PublishProcessor.create<AddBoardsControllerState>()
    .toSerialized()

  @Inject
  lateinit var siteManager: SiteManager
  @Inject
  lateinit var boardManager: BoardManager

  private val selectedBoards = LinkedHashSet<BoardDescriptor>()

  override fun onCreate(view: AddBoardsView) {
    super.onCreate(view)

    Chan.inject(this)

    selectedBoards.clear()
    showInactiveBoards()
  }

  fun listenForStateChanges(): Flowable<AddBoardsControllerState> {
    return stateSubject
      .onBackpressureLatest()
      .observeOn(AndroidSchedulers.mainThread())
      .doOnError { error ->
        Logger.e(TAG, "Unknown error subscribed to stateSubject.listenForStateChanges()", error)
      }
      .onErrorReturn { error -> AddBoardsControllerState.Error(error.errorMessageOrClassName()) }
      .hide()
  }

  private fun showInactiveBoards() {
    scope.launch(Dispatchers.Default) {
      setState(AddBoardsControllerState.Loading)

      boardManager.awaitUntilInitialized()
      siteManager.awaitUntilInitialized()

      val site = siteManager.bySiteDescriptor(siteDescriptor)
      if (site == null) {
        setState(AddBoardsControllerState.Error("No site found by descriptor: ${siteDescriptor}"))
        return@launch
      }

      val isSiteActive = siteManager.isSiteActive(siteDescriptor)
      if (!isSiteActive) {
        setState(AddBoardsControllerState.Error("Site with descriptor ${siteDescriptor} is not active!"))
        return@launch
      }

      showBoardsWithSearchQuery()
    }
  }

  fun onBoardSelectionChanged(boardDescriptor: BoardDescriptor, checked: Boolean, query: String) {
    if (checked) {
      selectedBoards += boardDescriptor
    } else {
      selectedBoards -= boardDescriptor
    }

    showBoardsWithSearchQuery(query)
  }

  suspend fun addSelectedBoards() {
    boardManager.activateDeactivateBoards(siteDescriptor, selectedBoards, true)

    selectedBoards.clear()
  }

  fun onSearchQueryChanged(query: String) {
    setState(AddBoardsControllerState.Loading)

    showBoardsWithSearchQuery(query)
  }

  private fun showBoardsWithSearchQuery(query: String = "") {
    val matchedBoards = mutableListWithCap<SelectableBoardCellData>(32)

    boardManager.viewAllBoards(siteDescriptor) { chanBoard ->
      if (chanBoard.active) {
        return@viewAllBoards
      }

      val isSelected = chanBoard.boardDescriptor in selectedBoards

      if (query.isNotEmpty()) {
        if (!chanBoard.boardCode().contains(query, ignoreCase = true)) {
          return@viewAllBoards
        }
      }

      matchedBoards += SelectableBoardCellData(
        boardCellData = BoardCellData(
          boardDescriptor = chanBoard.boardDescriptor,
          name = BoardHelper.getName(chanBoard),
          description = BoardHelper.getDescription(chanBoard)
        ),
        selected = isSelected
      )
    }

    if (matchedBoards.isEmpty()) {
      setState(AddBoardsControllerState.Empty)
      return
    }

    val comparator = BoardDescriptorsComparator<SelectableBoardCellData>(query) { selectableBoardCellData ->
      selectableBoardCellData.boardCellData.boardDescriptor
    }

    val sortedBoards = matchedBoards.sortedWith(comparator)
    setState(AddBoardsControllerState.Data(sortedBoards))
  }

  private fun setState(state: AddBoardsControllerState) {
    stateSubject.onNext(state)
  }

  companion object {
    private const val TAG = "AddBoardsPresenter"
  }
}