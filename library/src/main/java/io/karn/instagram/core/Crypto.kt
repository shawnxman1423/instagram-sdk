package io.karn.instagram.core

import io.karn.instagram.Instagram
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Crypto {
    private const val SIG_KEY = "44eb09cf8468b639a2c85f5040f83ac534db054bbb9264b6dafaa4436862266e"
    private const val SIG_VERSION = "4"

    internal const val DPI: String = "640dpi"
    internal const val DISPLAY_RESOLUTION: String = "1440x2560"

    private const val APP_ID = "567067343352427"
    private const val APP_VERSION = "110.0.0.16.119"
    private const val VERSION_CODE: String = "171727776"

    private val PIGEON_SESSION_ID = generateTemporaryGUID("pigeonSessionId", Instagram.session.uuid, 1200000f)

    val HEADERS: HashMap<String, String>
        get() {
            return hashMapOf(
                    "Accept-Encoding" to "gzip, deflate",
                    "Connection" to "keep-alive",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Accept-Language" to "en-US",
                    "Host" to "i.instagram.com",
                    "X-Pigeon-Session-Id" to PIGEON_SESSION_ID,
                    "X-Pigeon-Rawclienttime" to "%.3f".format(System.currentTimeMillis() / 1000f),
                    "X-IG-Connection-Speed" to  "-1kbps",
                    "X-IG-Bandwidth-Speed-KBPS" to  "-1.000",
                    "X-IG-Bandwidth-TotalBytes-B" to  "0",
                    "X-IG-Bandwidth-TotalTime-MS" to  "0",
                    "X-IG-Connection-Type" to "WIFI",
                    "X-IG-Capabilities" to "3brTvwE=",
                    "X-IG-App-ID" to APP_ID,
                    "X-FB-HTTP-Engine" to "Liger",
                    "X-Bloks-Version-Id" to "8a233a560240c2cffeb93421bb31df66e4f81471f77d74f7752ea441e9271665",
                    "User-Agent" to buildUserAgent()
            )
        }

    /**
     * Function to build the UserAgent which is used with the API to manage user authentication. This User Agent must be
     * correct otherwise the authentication step will fail.
     *
     * The User Agent's defaults are set below in the event that this function is exposed in the future. The parameters
     * that are known to work are as follows.
     *
     *  androidVersion = "24"
     *  androidRelease = "7.0"
     *  dpi = "640dpi"
     *  resolution = "1440x2560"
     *  manufacturer = "samsung"
     *  brand = ""
     *  device = "herolte"
     *  model = "SM-G930F"
     *  hardware = "samsungexynos8890"
     */
    fun buildUserAgent(androidVersion: Int = android.os.Build.VERSION.SDK_INT,
                       androidRelease: String = android.os.Build.VERSION.RELEASE,
                       dpi: String = Instagram.config.deviceDPI,
                       resolution: String = Instagram.config.deviceResolution,
                       manufacturer: String = android.os.Build.MANUFACTURER,
                       brand: String = android.os.Build.BRAND.takeIf { !it.isNullOrBlank() }?.let { "/$it" } ?: "",
                       device: String = android.os.Build.DEVICE,
                       model: String = android.os.Build.MODEL,
                       hardware: String = android.os.Build.HARDWARE): String {

        return "Instagram $APP_VERSION Android ($androidVersion/$androidRelease; $dpi; $resolution; $manufacturer$brand; $model; $device; $hardware; en_US; $VERSION_CODE)"
    }

    fun generateUUID(dash: Boolean): String {
        val uuid = UUID.randomUUID().toString()

        return if (dash) {
            uuid
        } else uuid.replace("-", "")
    }

    private fun generateTemporaryGUID(name: String, uuid: String, duration: Float): String {
        return UUID.nameUUIDFromBytes("$name$uuid${Math.round(System.currentTimeMillis() / duration)}".toByteArray()).toString()
    }

    fun generateLoginPayload(token: String, username: String, password: String, loginAttempts: Int, deviceId: String = generateDeviceId(username, password)): String {
        val data = JSONObject()
                .put("phone_id", generateUUID(true))
                .put("_csrftoken", token)
                .put("username", username)
                .put("guid", Instagram.session.uuid)
                .put("device_id", deviceId)
                .put("password", password)
                .put("login_attempt_count", loginAttempts)

        return generateSignature(data.toString())
    }

    fun generateTwoFactorPayload(code: String, identifier: String, token: String, username: String, password: String, deviceId: String = generateDeviceId(username, password)): String {
        val data = JSONObject()
                .put("verification_code", code)
                .put("two_factor_identifier", identifier)
                .put("_csrftoken", token)
                .put("username", username)
                .put("device_id", deviceId)
                .put("password", password)

        return generateSignature(data.toString())
    }

    fun generateAuthenticatedParams(session: Session, mutate: (JSONObject) -> Unit = {}): String {
        val data = JSONObject()
                .put("_uuid", session.uuid)
                .put("_uid", session.primaryKey)
                .put("_csrftoken", session.cookieJar.getCookie("csrftoken")?.value?.toString() ?: "")

        mutate(data)

        return generateSignature(data.toString())
    }

    fun generateAuthenticatedParamsV2(session: Session, mutate: (JSONObject) -> Unit = {}): String {
        val data = JSONObject()
                .put("guid", session.uuid)
                .put("device_id", session.deviceId)
                .put("_csrftoken", session.cookieJar.getCookie("csrftoken")?.value?.toString() ?: "")

        mutate(data)

        return generateSignature(data.toString())
    }

    private fun digest(codec: String, source: String): String {
        val digest = MessageDigest.getInstance(codec)
        val digestBytes = digest.digest(source.toByteArray(Charset.forName("UTF-8")))

        return bytesToHex(digestBytes)
    }

    private fun md5Hex(source: String): String = digest("MD5", source)

    fun generateDeviceId(username: String, password: String): String {
        val seed = md5Hex(username + password)
        val volatileSeed = "12345"

        return "android-" + md5Hex(seed + volatileSeed).substring(0, 16)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val builder = StringBuilder()

        bytes.forEach { builder.append(String.format("%02x", it)) }

        return builder.toString()
    }

    private fun generateSignedBody(key: String, data: String): String {
        val sha256HMAC = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charset.forName("UTF-8")), "HmacSHA256")
        sha256HMAC.init(secretKey)

        return bytesToHex(sha256HMAC.doFinal(data.toByteArray(Charset.forName("UTF-8")))).toLowerCase()
    }

    private fun generateSignature(payload: String): String {
        val parsedData = URLEncoder.encode(payload, "UTF-8")

        val signedBody = generateSignedBody(SIG_KEY, payload)

        return ("signed_body=$signedBody.$parsedData&ig_sig_key_version=$SIG_VERSION")
    }
}
