package app.lawnchair.ui.preferences.data.liveinfo

import app.lawnchair.ui.preferences.data.liveinfo.model.LiveInformation
import retrofit2.Response

val liveInformationService: LiveInformationService = object : LiveInformationService {
    override suspend fun getLiveInformation(endpoint: String): Response<LiveInformation> {
        return Response.success(LiveInformation())
    }
}

suspend fun getLiveInformation(endpoint: String): LiveInformation? = null
