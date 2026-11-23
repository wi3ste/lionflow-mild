package no.lionflow.app.api

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.lionflow.app.data.SecureStore
import no.lionflow.app.data.SettingsStore
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

data class BrandConfig(
    val oauthUrl: String,
    val realm: String,
    val scheme: String,
    val locale: String,
    val clientId: String,
    val clientSecret: String,
    val culture: String,
) {
    val basicToken: String
        get() = Base64.encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val redirectUri: String get() = "$scheme://oauth2redirect/$culture"
}

object StellantisConfig {
    private var cache: JSONObject? = null

    private fun root(ctx: Context): JSONObject {
        cache?.let { return it }
        val text = ctx.assets.open("stellantis_configs.json").bufferedReader().use { it.readText() }
        return JSONObject(text).also { cache = it }
    }

    fun countries(ctx: Context, brand: no.lionflow.app.model.Brand): List<String> {
        val cfgs = root(ctx).getJSONObject(brand.jsonKey).getJSONObject("configs")
        return cfgs.keys().asSequence().toList().sorted()
    }

    fun load(ctx: Context, brand: no.lionflow.app.model.Brand, country: String): BrandConfig {
        val app = root(ctx).getJSONObject(brand.jsonKey)
        val cfgs = app.getJSONObject("configs")
        // Not every brand is available in every country: fall back to Norway, then to the first one listed.
        val cc = listOf(country, "NO", "GB").firstOrNull { cfgs.has(it) } ?: cfgs.keys().next()
        val c = cfgs.getJSONObject(cc)
        return BrandConfig(
            oauthUrl = app.getString("oauth_url"),
            realm = app.getString("realm"),
            scheme = app.getString("scheme"),
            locale = c.getString("locale"),
            clientId = c.getString("client_id"),
            clientSecret = c.getString("client_secret"),
            culture = cc.lowercase(),
        )
    }
}

internal enum class MildHybridDetection(val detected: Boolean = false) {
    VehicleMetadata(true), ModelName(true), OtherPowertrain, InsufficientMetadata,
}

data class Vehicle(
    val id: String,
    val vin: String,
    val motorization: String,
    val brand: String,
    val pictureUrl: String?,
    val maintenanceHref: String?,
    val modelLabel: String? = null,
    internal val legacyLabel: String? = null,
    internal val typeVehicleReported: Boolean? = null,
    internal val brandingDetailReceived: Boolean? = null,
) {
    internal val needsBranding: Boolean
        get() = (brand.isBlank() || brand.equals("Peugeot", ignoreCase = true)) &&
            mildHybridDetection == MildHybridDetection.InsufficientMetadata

    val reportsMildHybrid: Boolean
        get() = mildHybridDetection.detected

    internal fun isMildHybrid(manualOverride: Boolean?): Boolean = manualOverride ?: reportsMildHybrid

    internal val mildHybridDetection: MildHybridDetection
        get() {
            val powertrain = motorization.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
            if (powertrain in mildHybridMotorizations) return MildHybridDetection.VehicleMetadata
            if (powertrain in otherMotorizations) return MildHybridDetection.OtherPowertrain
            val labels = listOfNotNull(modelLabel, legacyLabel).map {
                labelSeparator.replace(it.lowercase(Locale.ROOT), " ").trim()
            }
            if (labels.any(otherModel::containsMatchIn)) return MildHybridDetection.OtherPowertrain
            // The complete Peugeot model and transmission distinguish this version from a generic Hybrid.
            if (powertrain in ambiguousMotorizations && brand.equals("Peugeot", ignoreCase = true) &&
                labels.any(hybrid208::containsMatchIn)) return MildHybridDetection.ModelName
            return MildHybridDetection.InsufficientMetadata
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("vin", vin); put("motorization", motorization); put("brand", brand)
        put("picture", pictureUrl ?: ""); put("maintenance", maintenanceHref ?: "")
        modelLabel?.let { put("modelLabel", it) }
        legacyLabel?.let { put("legacyLabel", it) }
        typeVehicleReported?.let { put("typeVehicleReported", it) }
        brandingDetailReceived?.let { put("brandingDetailReceived", it) }
    }

    internal fun withBrandingDetail(j: JSONObject?): Vehicle {
        if (j == null || j.metadataString("id")?.let { it != id } == true ||
            j.metadataString("vin")?.let { it != vin } == true) {
            return copy(brandingDetailReceived = false)
        }
        return withBranding(j).copy(brandingDetailReceived = true)
    }

    private fun withBranding(j: JSONObject): Vehicle {
        val extension = j.obj("_embedded", "extension")
        val branding = extension?.optJSONObject("branding")
        val brandingLabel = branding?.metadataString("label")
        val label = j.metadataString("label")
        return copy(
            motorization = j.metadataString("motorization") ?: motorization,
            brand = branding?.metadataString("brand") ?: j.metadataString("brand") ?: brand,
            modelLabel = brandingLabel ?: label ?: modelLabel,
            legacyLabel = if (brandingLabel == null && label == null) legacyLabel
                else label?.takeIf { brandingLabel != null && it != brandingLabel },
            // Keep only the presence of the undocumented field across both responses.
            typeVehicleReported = typeVehicleReported == true ||
                listOfNotNull(j, extension, branding).any { !it.isNull("typeVehicle") },
        )
    }

    companion object {
        private val mildHybridMotorizations = setOf(
            "mhev", "mhev48v", "48vmhev", "mildhybrid", "mildhybridelectricvehicle", "48vmildhybrid", "mildhybrid48v",
            "48vhybrid", "hybrid48v",
        )
        private val otherMotorizations = setOf(
            "phev", "pluginhybrid", "pluginhybridelectricvehicle", "bev", "electric", "batteryelectricvehicle",
            "thermic", "thermal", "fuel", "petrol", "gasoline", "diesel", "ice", "hydrogen",
        )
        private val ambiguousMotorizations = setOf("", "hybrid", "hev", "unknown")
        private val labelSeparator = Regex("""[^\p{L}\p{N}]+""")
        private val otherModel = Regex("""\b(?:phev|bev|plug ?in|electric|e ?208)\b""")
        private val hybrid208 = Regex("""\b208(?: (?![0-9]{3,4}\b)[\p{L}\p{N}]+)*? hybrid 100 e ?dcs ?6\b""")

        private fun JSONObject.metadataString(key: String): String? =
            (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

        fun fromJson(j: JSONObject) = Vehicle(
            id = j.getString("id"), vin = j.getString("vin"),
            motorization = j.metadataString("motorization").orEmpty(), brand = j.metadataString("brand").orEmpty(),
            pictureUrl = j.optString("picture").ifEmpty { null },
            maintenanceHref = j.optString("maintenance").ifEmpty { null },
            modelLabel = j.metadataString("modelLabel"),
            legacyLabel = j.metadataString("legacyLabel"),
            typeVehicleReported = j.opt("typeVehicleReported") as? Boolean,
            brandingDetailReceived = j.opt("brandingDetailReceived") as? Boolean,
        )

        internal fun fromApi(j: JSONObject, defaultBrand: String): Vehicle = Vehicle(
            id = j.getString("id"),
            vin = j.getString("vin"),
            motorization = "",
            brand = defaultBrand,
            pictureUrl = j.optJSONArray("pictures")?.optString(0)?.ifEmpty { null },
            maintenanceHref = j.obj("_links", "maintenance")?.str("href"),
        ).withBranding(j)
    }
}

class AuthExpiredException : Exception("Signed out — please sign in again")

/**
 * The Stellantis (ex-PSA) connected-car API, used the same way the MyPeugeot app uses it.
 * Endpoints and flow follow the MIT-licensed homeassistant-stellantis-vehicles project.
 */
class StellantisApi(private val ctx: Context, private val secure: SecureStore, private val settings: SettingsStore) {

    private val tokenLock = Mutex()
    val cfg: BrandConfig get() = StellantisConfig.load(ctx, no.lionflow.app.model.Brand.of(settings.value.brand), settings.value.country)

    val isSignedIn: Boolean get() = secure.get(K_REFRESH) != null
    val customerId: String? get() = secure.get(K_CUSTOMER)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clientQuery() = "client_id=${cfg.clientId}&locale=${cfg.locale}"

    fun authorizeUrl(): String =
        "${cfg.oauthUrl}/am/oauth2/authorize?client_id=${cfg.clientId}&response_type=code" +
            "&redirect_uri=${cfg.redirectUri}&scope=openid%20profile%20email&locale=${cfg.locale}"

    // ---------------- OAuth ----------------

    suspend fun exchangeCode(code: String) {
        val url = "${cfg.oauthUrl}/am/oauth2/access_token?redirect_uri=${cfg.redirectUri}" +
            "&grant_type=authorization_code&code=${enc(code)}"
        tokenRequest(url)
    }

    private suspend fun refreshOauth() {
        val refresh = secure.get(K_REFRESH) ?: throw AuthExpiredException()
        val url = "${cfg.oauthUrl}/am/oauth2/access_token?grant_type=refresh_token&refresh_token=${enc(refresh)}"
        try {
            tokenRequest(url)
        } catch (e: ApiException) {
            if (e.code == 400 || e.code == 401) throw AuthExpiredException()
            throw e
        }
    }

    private suspend fun tokenRequest(url: String) {
        val req = Request.Builder().url(url)
            .post(ByteArray(0).toRequestBody(null))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic ${cfg.basicToken}")
            .build()
        val j = Http.json(req) as? JSONObject ?: throw ApiException(0, "Empty token response")
        val access = j.str("access_token") ?: throw ApiException(0, "No access token in response")
        secure.put(K_ACCESS, access)
        j.str("refresh_token")?.let { secure.put(K_REFRESH, it) }
        val expiresIn = j.optLong("expires_in", 3600L)
        secure.put(K_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000).toString())
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val exp = secure.get(K_EXPIRES)?.toLongOrNull() ?: 0L
        if (secure.get(K_ACCESS) == null || System.currentTimeMillis() > exp - 5 * 60_000) refreshOauth()
        secure.get(K_ACCESS) ?: throw AuthExpiredException()
    }

    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val token = accessToken()
        return try {
            block(token)
        } catch (e: ApiException) {
            if (e.code == 401) {
                tokenLock.withLock { refreshOauth() }
                block(secure.get(K_ACCESS) ?: throw AuthExpiredException())
            } else throw e
        }
    }

    private fun otpHeaders(b: Request.Builder, token: String): Request.Builder = b
        .header("Authorization", "Bearer $token")
        .header("User-Agent", "okhttp/4.8.0")
        .header("Accept", "application/hal+json")
        .header("x-introspect-realm", cfg.realm)

    private fun carHeaders(b: Request.Builder, token: String): Request.Builder = b
        .header("Authorization", "Bearer $token")
        .header("x-introspect-realm", cfg.realm)

    // ---------------- account & vehicles ----------------

    /** Returns the customer id and stores it. */
    suspend fun loadUserInfo(): String? = authed { token ->
        val url = "$API/applications/cvs/v4/mauv/car-associations?${clientQuery()}"
        val req = otpHeaders(Request.Builder().url(url), token).header("x-transaction-id", "1234").get().build()
        val arr = Http.json(req) as? JSONArray ?: JSONArray()
        // Some cars (e.g. newer models) have no remote-services link here; status still works without it.
        val customer = arr.objects().map { it.optString("customer") }.firstOrNull { it.isNotEmpty() }
        if (customer != null) secure.put(K_CUSTOMER, customer)
        customer
    }

    suspend fun vehicles(checkSelectedBranding: Boolean = false): List<Vehicle> {
        val requestProfile = profile
        return authed { token ->
            if (profile != requestProfile) return@authed cachedVehiclesOf(requestProfile)
            val defaultBrand = no.lionflow.app.model.Brands.current.display
            val before = cachedVehiclesOf(requestProfile)
            val url = "$API/connectedcar/v4/user/vehicles?${clientQuery()}&extension=branding".toHttpUrl()
            val j = Http.json(carHeaders(Request.Builder().url(url), token).get().build()) as? JSONObject
                ?: throw ApiException(0, "Empty vehicle list")
            val list = j.obj("_embedded")?.optJSONArray("vehicles")?.objects().orEmpty()
                .map { Vehicle.fromApi(it, defaultBrand) }
                // The same car can come twice (for example when it is shared with another account): keep the one in use.
                .groupBy { it.vin }.values.map { same ->
                    same.firstOrNull { v -> before.any { it.id == v.id } } ?: same.first()
                }
            if (profile != requestProfile) return@authed list
            val selected = if (checkSelectedBranding) pick(list)?.takeIf {
                it.mildHybridDetection == MildHybridDetection.InsufficientMetadata
            } else null
            val updated = if (selected == null) list else {
                val detailUrl = url.newBuilder().addPathSegment(selected.id).build()
                val request = carHeaders(Request.Builder().url(detailUrl), token).get().build()
                val detailed = selected.withBrandingDetail(optionalBrandingDetail(request))
                list.map { if (it === selected) detailed else it }
            }
            if (profile == requestProfile) secure.put(K_VEHICLES, JSONArray(updated.map { it.toJson() }).toString())
            updated
        }
    }

    private suspend fun optionalBrandingDetail(request: Request): JSONObject? = try {
        Http.json(request) as? JSONObject
    } catch (_: ApiException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /** Cars cached for another account (used by the car list in Settings). */
    fun cachedVehiclesOf(profile: String): List<Vehicle> =
        secure.get(K_VEHICLES, profile)?.let { s -> JSONArray(s).objects().map { Vehicle.fromJson(it) } }.orEmpty()
    fun selectedVinOf(profile: String): String? = secure.get(K_SELECTED, profile)?.ifEmpty { null }
    fun isSignedInTo(profile: String): Boolean = secure.get(K_REFRESH, profile) != null
    fun switchProfile(profile: String) { secure.profile = profile }
    val profile: String get() = secure.profile

    fun cachedVehicles(): List<Vehicle> = cachedVehiclesOf(profile)

    /** The car chosen in the app (accounts can have several). */
    var selectedVin: String?
        get() = secure.get(K_SELECTED)?.ifEmpty { null }
        set(v) = secure.put(K_SELECTED, v ?: "")

    fun pick(list: List<Vehicle>): Vehicle? = list.firstOrNull { it.vin == selectedVin } ?: list.firstOrNull()
    fun cachedVehicle(): Vehicle? = pick(cachedVehicles())
    suspend fun currentVehicle(): Vehicle? = cachedVehicle() ?: pick(vehicles())

    fun isMildHybrid(vehicle: Vehicle): Boolean =
        vehicle.isMildHybrid(settings.mildHybridOverride(vehicle.vin))

    fun parseStatus(vehicle: Vehicle, status: JSONObject, maintenance: JSONObject? = null): VehicleStatus =
        VehicleStatus.parse(status, maintenance, isMildHybrid(vehicle))

    suspend fun readStatus(vehicle: Vehicle): VehicleStatus = parseStatus(vehicle, status(vehicle))

    fun parseTrips(vehicle: Vehicle, trips: List<JSONObject>): List<Trip> {
        val mildHybrid = isMildHybrid(vehicle)
        return trips.map { Trip.parse(it, mildHybrid) }
    }

    suspend fun status(v: Vehicle): JSONObject = authed { token ->
        val url = "$API/connectedcar/v4/user/vehicles/${v.id}/status?${clientQuery()}"
        Http.json(carHeaders(Request.Builder().url(url), token).get().build()) as? JSONObject
            ?: throw ApiException(0, "Empty status response")
    }

    suspend fun maintenance(v: Vehicle): JSONObject? {
        val urls = listOfNotNull(v.maintenanceHref, "$API/connectedcar/v4/user/vehicles/${v.id}/maintenance").distinct()
        for (href in urls) {
            try {
                val j = authed { token ->
                    val sep = if (href.contains("?")) "&" else "?"
                    Http.json(carHeaders(Request.Builder().url("$href$sep${clientQuery()}"), token).get().build()) as? JSONObject
                }
                if (j != null && (j.has("mileageBeforeMaintenance") || j.has("daysBeforeMaintenance"))) return j
            } catch (e: AuthExpiredException) {
                throw e
            } catch (_: Exception) {
                // try the next address; service data is optional
            }
        }
        return null
    }

    private suspend fun trips(v: Vehicle, sinceIso: String, untilIso: String?, nextHref: String?): JSONObject = authed { token ->
        val endpoint = "$API/connectedcar/v4/user/vehicles/${v.id}/trips?${clientQuery()}".toHttpUrl()
        val url = tripPageUrl(endpoint, sinceIso, untilIso, nextHref)
        Http.json(carHeaders(Request.Builder().url(url), token).get().build()) as? JSONObject ?: JSONObject()
    }

    /** Trips are the server's answer: the trips (raw), and whether it said "no trips found" for the whole period. */
    class TripsResult(val trips: List<JSONObject>, val noneFound: Boolean)

    suspend fun allTrips(v: Vehicle, fromMillis: Long): TripsResult =
        loadWeeklyTrips(fromMillis, System.currentTimeMillis()) { from, to, next -> trips(v, from, to, next) }

    // ---------------- remote control setup ----------------

    suspend fun requestSmsCode() = authed { token ->
        val url = "$API/applications/cvs/v4/mobile/smsCode?${clientQuery()}"
        Http.text(otpHeaders(Request.Builder().url(url), token).post(ByteArray(0).toRequestBody(null)).build())
        Unit
    }

    /** POSTs to the virtual-key token endpoint (password grant with an OTP, or refresh grant). */
    suspend fun mqttToken(body: JSONObject): JSONObject = authed { token ->
        val url = "$API/connectedcar/v4/virtualkey/remoteaccess/token?${clientQuery()}"
        val req = otpHeaders(Request.Builder().url(url), token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.json(req) as? JSONObject ?: JSONObject()
    }

    /** First access token of the session — used as OTP device id like the reference implementation. */
    fun currentAccessToken(): String? = secure.get(K_ACCESS)

    fun signOut() {
        secure.clear()
    }

    companion object {
        const val API = "https://api.groupe-psa.com"
        const val K_ACCESS = "oauth_access"
        const val K_REFRESH = "oauth_refresh"
        const val K_EXPIRES = "oauth_expires"
        const val K_CUSTOMER = "customer_id"
        const val K_VEHICLES = "vehicles"
        const val K_SELECTED = "selected_vin"
    }
}
