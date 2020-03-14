package com.github.adamantcheese.database.source.remote

import com.github.adamantcheese.base.ModularResult
import com.github.adamantcheese.base.ModularResult.Companion.safeRun
import com.github.adamantcheese.database.common.Logger
import com.github.adamantcheese.database.data.internal.MediaServiceLinkExtraInfo
import com.github.adamantcheese.database.data.video_service.MediaServiceType
import com.github.adamantcheese.database.util.errorMessageOrClassName
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class MediaServiceLinkExtraContentRemoteSource(
        okHttpClient: OkHttpClient,
        loggerTag: String,
        logger: Logger
) : AbstractRemoteSource(okHttpClient, logger) {
    private val TAG = "$loggerTag MediaServiceLinkExtraContentRemoteSource"

    suspend fun fetchFromNetwork(
            requestUrl: String,
            mediaServiceType: MediaServiceType
    ): ModularResult<MediaServiceLinkExtraInfo> {
        logger.log(TAG, "fetchFromNetwork($requestUrl, $mediaServiceType)")

        return safeRun {
            val httpRequest = Request.Builder()
                    .url(requestUrl)
                    .get()
                    .build()

            val response = okHttpClient.suspendCall(httpRequest)
            return@safeRun extractMediaServiceLinkExtraInfo(
                    mediaServiceType,
                    response
            )
        }
    }

    private fun extractMediaServiceLinkExtraInfo(
            mediaServiceType: MediaServiceType,
            response: Response
    ): MediaServiceLinkExtraInfo {
        return response.use { resp ->
            return@use resp.body.use { body ->
                if (body == null) {
                    return MediaServiceLinkExtraInfo.empty()
                }

                val parser = JsonParser.parseString(body.string())

                val title = MediaServiceLinkExtraContentRemoteSourceHelper
                        .tryExtractVideoTitle(mediaServiceType, parser)
                        .peekError { error ->
                            logger.logError(TAG, "Error while trying to extract video " +
                                    "title for service ($mediaServiceType), " +
                                    "error = ${error.errorMessageOrClassName()}")
                        }
                        .valueOrNull()
                val duration = MediaServiceLinkExtraContentRemoteSourceHelper
                        .tryExtractVideoDuration(mediaServiceType, parser)
                        .peekError { error ->
                            logger.logError(TAG, "Error while trying to extract video " +
                                    "duration for service ($mediaServiceType), " +
                                    "error = ${error.errorMessageOrClassName()}")
                        }
                        .valueOrNull()

                return@use MediaServiceLinkExtraInfo(title, duration)
            }
        }
    }
}