package co.cben.dev.aiinlinereview.crit

class CritHttpException(val status: Int, body: String) : RuntimeException("crit HTTP $status: $body")

class UnsupportedCritApi(val apiVersion: Int) :
    RuntimeException("crit API v$apiVersion unsupported, plugin supports API v$SUPPORTED_API_VERSION")

const val SUPPORTED_API_VERSION = 1

interface CritApi {
    fun health(): Health
    fun config(): CritConfig
    fun session(): SessionInfo
    fun fileComments(path: String): List<Comment>

    fun requireSupported(): Health {
        val health = health()
        if (health.apiVersion != SUPPORTED_API_VERSION) throw UnsupportedCritApi(health.apiVersion)
        return health
    }
}
