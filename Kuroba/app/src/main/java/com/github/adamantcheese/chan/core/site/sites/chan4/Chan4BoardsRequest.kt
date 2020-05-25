/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.core.site.sites.chan4

import android.util.JsonReader
import com.github.adamantcheese.chan.core.model.orm.Board
import com.github.adamantcheese.chan.core.net.JsonReaderRequest
import com.github.adamantcheese.chan.core.repository.BoardRepository
import com.github.adamantcheese.chan.core.site.Site
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.*

class Chan4BoardsRequest(
  private val site: Site,
  request: Request,
  okHttpClient: OkHttpClient
) : JsonReaderRequest<BoardRepository.SiteBoards>(RequestType.Chan4BoardsRequest, request, okHttpClient) {
  
  override suspend fun readJson(reader: JsonReader): BoardRepository.SiteBoards {
    val list: MutableList<Board> = ArrayList()
    
    reader.withObject {
      while (hasNext()) {
        val key = nextName()
        if (key == "boards") {
          withArray {
            while (hasNext()) {
              val board = readBoardEntry(this)
              if (board != null) {
                list.add(board)
              }
            }
          }
        } else {
          skipValue()
        }
      }
    }
    
    return BoardRepository.SiteBoards(site, list)
  }
  
  @Throws(IOException::class)
  private fun readBoardEntry(reader: JsonReader): Board? {
    return reader.withObject {
      val board = Board()
      board.siteId = site.id()
      board.site = site
      
      while (hasNext()) {
        when (nextName()) {
          "title" -> board.name = nextString()
          "board" -> board.code = nextString()
          "ws_board" -> board.workSafe = nextInt() == 1
          "per_page" -> board.perPage = nextInt()
          "pages" -> board.pages = nextInt()
          "max_filesize" -> board.maxFileSize = nextInt()
          "max_webm_filesize" -> board.maxWebmSize = nextInt()
          "max_comment_chars" -> board.maxCommentChars = nextInt()
          "bump_limit" -> board.bumpLimit = nextInt()
          "image_limit" -> board.imageLimit = nextInt()
          "cooldowns" -> readCooldowns(this, board)
          "spoilers" -> board.spoilers = nextInt() == 1
          "custom_spoilers" -> board.customSpoilers = nextInt()
          "user_ids" -> board.userIds = nextInt() == 1
          "code_tags" -> board.codeTags = nextInt() == 1
          "country_flags" -> board.countryFlags = nextInt() == 1
          "math_tags" -> board.mathTags = nextInt() == 1
          "meta_description" -> board.description = nextString()
          "is_archived" -> board.archive = nextInt() == 1
          else -> skipValue()
        }
      }
  
      if (board.hasMissingInfo()) {
        // Invalid data, ignore
        return@withObject null
      }

      return@withObject board
    }
    
  }
  
  private fun readCooldowns(reader: JsonReader, board: Board) {
    reader.withObject {
      while (hasNext()) {
        when (nextName()) {
          "threads" -> board.cooldownThreads = nextInt()
          "replies" -> board.cooldownReplies = nextInt()
          "images" -> board.cooldownImages = nextInt()
          else -> skipValue()
        }
      }
    }
  }
  
  companion object {
    private const val TAG = "Chan4BoardsRequest"
  }
}