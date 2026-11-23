package no.lionflow.app.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.lionflow.app.data.SecureStore
import no.lionflow.app.data.SettingsStore
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

data class Vehicle(
    val id: String,
    val vin: String,
    val motorization: String,
    val brand: String,
    val pictureUrl: String?,
    val maintenanceHref: String?,
    val modelLabel: String? = null,
) {
    val reportsMildHybrid: Boolean
        get() {
            val powertrain = motorization.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
            if (powertrain in mildHybridMotorizations) return true
            // Peugeot's 208 Hybrid 100 e-DCS6 is a 48 V model. "Hybrid" or "208" alone is ambiguous.
            return powertrain == "hybrid" && brand.equals("Peugeot", ignoreCase = true) &&
                modelLabel?.let { hybrid208.containsMatchIn(it) } == true
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("vin", vin); put("motorization", motorization); put("brand", brand)
        put("picture", pictureUrl ?: ""); put("maintenance", maintenanceHref ?: "")
        modelLabel?.let { put("modelLabel", it) }
    }

    companion object {
        private val mildHybridMotorizations = setOf("mhev", "mildhybrid", "mildhybridelectricvehicle", "48vmildhybrid")
        private val hybrid208 = Regex("""\b208\s+hybrid\s+100\s+e[-‐‑– ]?dcs\s*6\b""", RegexOption.IGNORE_CASE)

        fun fromJson(j: JSONObject) = Vehicle(
            id = j.getString("id"), vin = j.getString("vin"),
            motorization = j.optString("motorization"), brand = j.optString("brand"),
            pictureUrl = j.optString("picture").ifEmpty { null },
            maintenanceHref = j.optString("maintenance").ifEmpty { null },
            modelLabel = j.str("modelLabel"),
        )

        internal fun fromApi(j: JSONObject, defaultBrand: String): Vehicle {
            val extension = j.obj("_embedded", "extension")
            val branding = extension?.optJSONObject("branding")
            return Vehicle(
                id = j.getString("id"),
                vin = j.getString("vin"),
                motorization = j.optString("motorization"),
                brand = branding?.str("brand")?.ifBlank { null } ?: j.optString("brand", defaultBrand),
                pictureUrl = j.optJSONArray("pictures")?.optString(0)?.ifEmpty { null },
                maintenanceHref = j.obj("_links", "maintenance")?.str("href"),
                modelLabel = branding?.str("label")?.ifBlank { null } ?: j.str("label"),
            )
        }
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

    suspend fun vehicles(): List<Vehicle> = authed { token ->
        val url = "$API/connectedcar/v4/user/vehicles?${clientQuery()}&extension=branding"
        val j = Http.json(carHeaders(Request.Builder().url(url), token).get().build()) as? JSONObject
            ?: throw ApiException(0, "Empty vehicle list")
        val before = cachedVehicles()
        val list = j.obj("_embedded")?.optJSONArray("vehicles")?.objects().orEmpty().map { v ->
            Vehicle.fromApi(v, no.lionflow.app.model.Brands.current.display)
        }
            // The same car can come twice (for example when it is shared with another account): keep the one in use.
            .groupBy { it.vin }.values.map { same -> same.firstOrNull { v -> before.any { it.id == v.id } } ?: same.first() }
        secure.put(K_VEHICLES, JSONArray(list.map { it.toJson() }).toString())
        list
    }

    /** Cars cached for another account (used by the car list in Settings). */
    fun cachedVehiclesOf(profile: String): List<Vehicle> =
        secure.get(K_VEHICLES, profile)?.let { s -> JSONArray(s).objects().map { Vehicle.fromJson(it) } }.orEmpty()
    fun selectedVinOf(profile: String): String? = secure.get(K_SELECTED, profile)?.ifEmpty { null }
    fun isSignedInTo(profile: String): Boolean = secure.get(K_REFRESH, profile) != null
    fun switchProfile(profile: String) { secure.profile = profile }
    val profile: String get() = secure.profile

    fun cachedVehicles(): List<Vehicle> =
        secure.get(K_VEHICLES)?.let { s -> JSONArray(s).objects().map { Vehicle.fromJson(it) } }.orEmpty()

    /** The car chosen in the app (accounts can have several). */
    var selectedVin: String?
        get() = secure.get(K_SELECTED)?.ifEmpty { null }
        set(v) = secure.put(K_SELECTED, v ?: "")

    fun pick(list: List<Vehicle>): Vehicle? = list.firstOrNull { it.vin == selectedVin } ?: list.firstOrNull()
    fun cachedVehicle(): Vehicle? = pick(cachedVehicles())
    suspend fun currentVehicle(): Vehicle? = cachedVehicle() ?: pick(vehicles())

    fun isMildHybrid(vehicle: Vehicle): Boolean =
        settings.mildHybridOverride(vehicle.vin) ?: vehicle.reportsMildHybrid

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

    /** One page of trips since the given ISO timestamp (and until [untilIso]), or the page at [nextHref] (the server's own "next" link). */
    suspend fun trips(v: Vehicle, sinceIso: String?, untilIso: String? = null, nextHref: String? = null): JSONObject = authed { token ->
        var url = if (nextHref != null && nextHref.startsWith(API)) {
            if (nextHref.contains("client_id=")) nextHref else nextHref + (if (nextHref.contains('?')) "&" else "?") + clientQuery()
        } else {
            var u = "$API/connectedcar/v4/user/vehicles/${v.id}/trips?${clientQuery()}&distance=0.1-&pageSize=60"
            if (sinceIso != null) u += "&timestamps=$sinceIso/${untilIso.orEmpty()}"
            u
        }
        Http.json(carHeaders(Request.Builder().url(url), token).get().build()) as? JSONObject ?: JSONObject()
    }

    /** Trips are the server's answer: the trips (raw), and whether it said "no trips found" for the whole period. */
    class TripsResult(val trips: List<JSONObject>, val noneFound: Boolean)

    /**
     * All trips from [fromMillis] until now. Asked for week by week, each week following the server's own "next" links:
     * asking for the whole period at once could stop after the first page and leave out the newest trips.
     */
    suspend fun allTrips(v: Vehicle, fromMillis: Long): TripsResult = kotlinx.coroutines.coroutineScope {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val week = 7 * 86_400_000L
        val windows = ArrayList<Pair<Long, Long?>>()
        var end: Long? = null
        var start = now - week
        while (true) {
            windows += maxOf(start, fromMillis) to end
            if (start <= fromMillis) break
            end = start; start -= week
        }
        val none = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val lists = windows.map { (from, to) -> async { gate.acquire(); try {
            val got = LinkedHashMap<String, JSONObject>()
            var next: String? = null
            var n = 0
            do {
                // "404 / 40400 No trip was found" just means no trips in this period (some cars say so on every call).
                val j = try { synchronized(iso) { Triple(iso.format(java.util.Date(from)), to?.let { iso.format(java.util.Date(it)) }, next) }.let { (a, b, c) -> trips(v, a, b, c) } }
                catch (e: ApiException) { if (e.code == 404) { if (n == 0) none.incrementAndGet(); break } else throw e }
                val arr = j.optJSONObject("_embedded")?.optJSONArray("trips")
                var fresh = 0
                if (arr != null) for (i in 0 until arr.length()) { val t = arr.optJSONObject(i) ?: continue; if (got.put(t.optString("id"), t) == null) fresh++ }
                next = j.optJSONObject("_links")?.optJSONObject("next")?.optString("href")?.takeIf { it.isNotBlank() }
                n++
            } while (next != null && fresh > 0 && n < 20)
            got.values.toList()
        } finally { gate.release() } } }.awaitAll()
        TripsResult(lists.flatten().distinctBy { it.optString("id") }, none.get() == windows.size)
    }

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
