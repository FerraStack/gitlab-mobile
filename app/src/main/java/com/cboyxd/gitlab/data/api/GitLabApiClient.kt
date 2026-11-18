package com.cboyxd.gitlab.data.api

import com.cboyxd.gitlab.BuildConfig
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response as OkHttpResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

object GitLabApiClient {

  internal const val MAX_REQUESTS_PER_HOST = 4
  internal const val MAX_RETRY_AFTER_SECONDS = 30L

  val moshi: Moshi = Moshi.Builder()
    .add(NullsAsDefaultsFactory)
    .add(KotlinJsonAdapterFactory())
    .build()

  /** One connection pool and dispatcher for every server; per-instance clients only add auth. */
  val baseHttpClient: OkHttpClient by lazy {
    val dispatcher = Dispatcher().apply {
      maxRequests = 32
      maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }
    OkHttpClient.Builder()
      .dispatcher(dispatcher)
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(60, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .apply {
        if (BuildConfig.DEBUG) {
          addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
      }
      .build()
  }

  /** Tests point this at a client that trusts MockWebServer's certificate. */
  @Volatile
  internal var clientOverride: OkHttpClient? = null

  @Volatile
  private var httpCache: Cache? = null

  @Volatile
  private var onlineCheck: () -> Boolean = { true }

  /** Configures a shared OkHttp disk cache for API GET responses and offline fallback. */
  fun configureCache(
    cacheDir: File,
    maxBytes: Long = 20L * 1024 * 1024,
    isOnline: () -> Boolean = { true },
  ) {
    val previous = httpCache
    httpCache = Cache(cacheDir, maxBytes)
    onlineCheck = isOnline
    services.clear()
    try {
      previous?.close()
    } catch (_: Exception) {
    }
  }

  fun clearDiskCache() {
    try {
      httpCache?.evictAll()
    } catch (_: Exception) {
    }
  }

  internal fun clearCache() = services.clear()

  internal fun resetCacheForTest() {
    val previous = httpCache
    httpCache = null
    onlineCheck = { true }
    services.clear()
    try {
      previous?.close()
    } catch (_: Exception) {
    }
  }

  private data class Entry(val key: String, val service: GitLabApiService)

  /** Keyed by instance id so a refreshed token replaces the old service instead of piling up. */
  private val services = ConcurrentHashMap<String, Entry>()

  fun createService(instance: GitLabInstance): GitLabApiService {
    val baseUrl = normalizeBaseUrl(instance.url)
    val key = baseUrl + "\u0000" + instance.token.trim() + "\u0000" + instance.authKind
    val slot = instance.id.ifBlank { baseUrl }
    services[slot]?.takeIf { it.key == key }?.let { return it.service }
    val service = buildService(baseUrl, instance.token, instance.authKind)
    services[slot] = Entry(key, service)
    return service
  }

  /**
   * For big downloads (job artifacts): no overall deadline, since a large archive on a slow
   * connection can take minutes. The read timeout still ends a stalled transfer. Not cached.
   */
  fun createDownloadService(instance: GitLabInstance): GitLabApiService =
    buildService(normalizeBaseUrl(instance.url), instance.token, instance.authKind, download = true)

  fun forget(instanceId: String) {
    services.remove(instanceId)
  }

  private fun buildService(baseUrl: String, token: String, authKind: String, download: Boolean = false): GitLabApiService {
    val authInterceptor = Interceptor { chain ->
      val request = chain.request().newBuilder()
        .header("User-Agent", "GitLabMobile/1.0 (Android)")
        .header("Accept", if (download) "*/*" else "application/json")
        .apply { applyToken(token, authKind) }
        .build()
      chain.proceed(request)
    }

    val offlineCacheInterceptor = Interceptor { chain ->
      val request = chain.request()
      if (download || httpCache == null || !request.method.equals("GET", ignoreCase = true)) {
        return@Interceptor chain.proceed(request)
      }

      fun tryCachedResponse(): OkHttpResponse? {
        val cachedReq = request.newBuilder()
          .removeHeader("Cache-Control")
          .removeHeader("Pragma")
          .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(7, TimeUnit.DAYS).build())
          .build()
        val cached = chain.proceed(cachedReq)
        return if (cached.code != 504) cached else {
          cached.close()
          null
        }
      }

      if (!onlineCheck()) {
        tryCachedResponse()?.let { return@Interceptor it }
      }

      try {
        val response = chain.proceed(request)
        if (response.code in setOf(500, 502, 503, 504)) {
          val contentType = response.body?.contentType()
          val errorBytes = try {
            response.body?.bytes() ?: ByteArray(0)
          } catch (_: IOException) {
            ByteArray(0)
          }
          response.close()
          tryCachedResponse()?.let { cached ->
            return@Interceptor cached
          }
          return@Interceptor response.newBuilder()
            .body(errorBytes.toResponseBody(contentType))
            .build()
        }
        response
      } catch (e: IOException) {
        tryCachedResponse() ?: throw e
      }
    }

    val cacheableGetNetworkInterceptor = Interceptor { chain ->
      val request = chain.request()
      val response = chain.proceed(request)
      if (!download && httpCache != null && request.method.equals("GET", ignoreCase = true) && response.isSuccessful) {
        val cacheControl = response.header("Cache-Control").orEmpty()
        val uncacheable = cacheControl.isBlank() ||
          cacheControl.contains("no-store", ignoreCase = true) ||
          cacheControl.contains("no-cache", ignoreCase = true) ||
          cacheControl.contains("must-revalidate", ignoreCase = true) ||
          cacheControl.contains("max-age=0", ignoreCase = true)
        if (uncacheable) {
          return@Interceptor response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "private, max-age=0")
            .build()
        }
      }
      response
    }

    val client = (clientOverride ?: baseHttpClient).newBuilder()
      .apply {
        if (!download) {
          httpCache?.let { cache(it) }
        }
      }
      .addInterceptor(authInterceptor)
      .addInterceptor(rateLimitInterceptor())
      .apply {
        if (!download) {
          addInterceptor(offlineCacheInterceptor)
          addNetworkInterceptor(cacheableGetNetworkInterceptor)
        } else {
          callTimeout(0, TimeUnit.MILLISECONDS)
        }
      }
      .build()

    return Retrofit.Builder()
      .baseUrl(baseUrl)
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(GitLabApiService::class.java)
  }
}

/** Retries idempotent GET/HEAD requests once when the server returns 429 with a bounded Retry-After header. */
internal fun rateLimitInterceptor(sleepMillis: (Long) -> Unit = Thread::sleep): Interceptor = Interceptor { chain ->
  val request = chain.request()
  val response = chain.proceed(request)
  if (response.code != 429 || (request.method != "GET" && request.method != "HEAD")) {
    return@Interceptor response
  }
  val delaySeconds = parseRetryAfterSeconds(response.header("Retry-After")) ?: return@Interceptor response
  response.close()
  if (delaySeconds > 0L) {
    try {
      sleepMillis(delaySeconds * 1_000L)
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw InterruptedIOException("Interrupted while waiting for rate limit retry").apply { initCause(interrupted) }
    }
  }
  chain.proceed(request)
}

internal fun parseRetryAfterSeconds(header: String?): Long? {
  val seconds = header?.trim()?.toLongOrNull() ?: return null
  return seconds.takeIf { it in 0L..GitLabApiClient.MAX_RETRY_AFTER_SECONDS }
}

/**
 * GitLab sends explicit `null` for many fields the models declare non-null with a default
 * (`author`, `name`, `ref`…). Codegen adapters reject that and one bad element used to fail a
 * whole list. This drops null members before decoding so the declared defaults apply.
 */
private object NullsAsDefaultsFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    val raw = Types.getRawType(type)
    if (!raw.isAnnotationPresent(JsonClass::class.java)) return null
    val delegate = moshi.nextAdapter<Any>(this, type, annotations)
    return object : JsonAdapter<Any>() {
      override fun fromJson(reader: JsonReader): Any? {
        val value = reader.readJsonValue()
        val cleaned = if (value is Map<*, *>) splitLabelDetails(value.filterValues { it != null }) else value
        return delegate.fromJsonValue(cleaned)
      }

      override fun toJson(writer: JsonWriter, value: Any?) = delegate.toJson(writer, value)
    }.nullSafe()
  }
}

/**
 * With `with_labels_details=true` GitLab sends labels as objects. Keep `labels` as the list of
 * names every screen uses and expose the objects (with colors) as `label_details`.
 */
private fun splitLabelDetails(map: Map<*, *>): Map<*, *> {
  val labels = map["labels"] as? List<*> ?: return map
  if (labels.none { it is Map<*, *> }) return map
  val names = labels.mapNotNull { (it as? Map<*, *>)?.get("name") as? String ?: it as? String }
  val details = labels.filterIsInstance<Map<*, *>>().map { m -> m.filterValues { it != null } }
  return map + mapOf("labels" to names, "label_details" to details)
}

private fun okhttp3.Request.Builder.applyToken(token: String, authKind: String) {
  val clean = token.trim()
  if (clean.isEmpty()) return
  if (authKind == GitLabAuth.KIND_OAUTH || clean.startsWith("Bearer ", ignoreCase = true)) {
    val value = if (clean.startsWith("Bearer ", ignoreCase = true)) clean.substringAfter(' ').trim() else clean
    header("Authorization", "Bearer $value")
  } else {
    header("PRIVATE-TOKEN", clean)
  }
}

fun <T> Response<T>.requireBody(): T {
  if (!isSuccessful) throw toGitLabException()
  return body() ?: throw GitLabApiException(code(), "The server sent an empty response.")
}

fun <T> Response<List<T>>.requireList(): List<T> {
  if (!isSuccessful) throw toGitLabException()
  return body().orEmpty()
}

fun Response<*>.toGitLabException(): GitLabApiException {
  val raw = try {
    errorBody()?.use { it.string().take(4_000) }
  } catch (_: Exception) {
    null
  }
  return GitLabApiException(code(), parseGitLabError(raw, code()))
}
