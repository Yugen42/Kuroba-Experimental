package com.github.adamantcheese.chan.features.search.data

import com.github.adamantcheese.chan.core.site.sites.search.SiteGlobalSearchType
import com.github.adamantcheese.model.data.descriptor.SiteDescriptor

internal sealed class GlobalSearchControllerState {
  object Loading : GlobalSearchControllerState()
  object Empty : GlobalSearchControllerState()
  data class Error(val errorText: String) : GlobalSearchControllerState()
  data class Data(val data: GlobalSearchControllerStateData) : GlobalSearchControllerState()
}

internal data class SelectedSite(
  val siteDescriptor: SiteDescriptor,
  val siteGlobalSearchType: SiteGlobalSearchType
)

internal data class SitesWithSearch(
  val sites: List<SiteDescriptor>,
  val selectedSite: SelectedSite
) {

  fun selectedItemIndex(): Int? {
    val index = sites.indexOfFirst { siteDescriptor -> siteDescriptor == selectedSite.siteDescriptor }
    if (index < 0 || index > sites.lastIndex) {
      return null
    }

    return index
  }

}

internal sealed class GlobalSearchControllerStateData(
  val sitesWithSearch: SitesWithSearch
) {
  class SitesSupportingSearchLoaded(sitesWithSearch: SitesWithSearch) : GlobalSearchControllerStateData(sitesWithSearch)
  class SearchQueryEntered(sitesWithSearch: SitesWithSearch, val query: String) : GlobalSearchControllerStateData(sitesWithSearch)
}
