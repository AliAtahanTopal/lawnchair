package app.lawnchair.bugreport

import kotlinx.serialization.Serializable

interface KatbinService {

    suspend fun upload(body: KatbinUploadBody): KatbinUploadResult

    companion object {
        fun create(): KatbinService = object : KatbinService {
            override suspend fun upload(body: KatbinUploadBody): KatbinUploadResult = KatbinUploadResult("")
        }
    }
}

@Serializable
data class KatbinUploadBody(
    val paste: KatbinPaste,
)

@Serializable
data class KatbinPaste(
    val content: String,
)

@Serializable
data class KatbinUploadResult(
    val id: String,
)
