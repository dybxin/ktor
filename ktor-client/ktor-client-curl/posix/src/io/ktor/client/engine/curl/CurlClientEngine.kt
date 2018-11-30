package io.ktor.client.engine.curl

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.*
import kotlinx.coroutines.io.*
import kotlin.collections.*
import kotlin.coroutines.*

class CurlClientEngine(override val config: CurlClientEngineConfig) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val coroutineContext: CoroutineContext = dispatcher + SupervisorJob()

    private val curlProcessor = CurlProcessor()

    private val responseConsumers: MutableMap<CurlRequestData, (CurlResponseData) -> Unit> = mutableMapOf()

    private val listener = object : WorkerListener<CurlResponse> {
        override fun update(data: CurlResponse) {
            data.completeResponses.forEach {
                val consumer = responseConsumers[it.request]!!
                consumer(it)
            }
        }
    }

    init {
        curlProcessor.start()
        launch(coroutineContext) {
            loop(curlProcessor)
        }
    }

    override suspend fun execute(
        call: HttpClientCall,
        data: HttpRequestData
    ): HttpEngineCall = suspendCancellableCoroutine { continuation ->
        val callContext = coroutineContext + CompletableDeferred<Unit>()
        val request = DefaultHttpRequest(call, data)
        val requestTime = GMTDate()

        val curlRequest = request.toCurlRequest()
        curlProcessor.addListener(curlRequest.listenerKey, listener)

        responseConsumers[curlRequest.newRequests.single()] = { curlResponseData ->
            val headers = curlResponseData.headers.parseResponseHeaders()

            val responseContext = writer(dispatcher, autoFlush = true) {
                for (chunk in curlResponseData.chunks) {
                    channel.writeFully(chunk, 0, chunk.size)
                }
            }

            val status = HttpStatusCode.fromValue(curlResponseData.status)

            val result = CurlHttpResponse(
                call, status, headers, requestTime,
                responseContext.channel, callContext, curlResponseData.version.fromCurl()
            )

            continuation.resume(HttpEngineCall(request, result))
        }

        curlProcessor.requestJob(curlRequest)
    }

    private fun eventLoopIteration(curlProcessor: CurlProcessor) {
        val key = ListenerKey()
        curlProcessor.requestJob(CurlRequest(emptyList(), key))
        curlProcessor.addListener(key, listener)
        curlProcessor.check(config.workerResponseStandBy)
    }

    private suspend fun loop(curlProcessor: CurlProcessor) {
        eventLoopIteration(curlProcessor)
        delay(config.workerNextIterationDelay)
        loop(curlProcessor)
    }

    override fun close() {
        curlProcessor.close()
        coroutineContext.cancel()
    }
}