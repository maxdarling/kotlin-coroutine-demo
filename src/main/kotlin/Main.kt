import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

fun main() {
    runBlocking {
        val demos = listOf<suspend () -> Unit>(
            { sharedContextDemo() }, // demo #1
            { timeoutDemo() }        // demo #2
        )

        val demo = demos[0]
        println("total elapsed time: ${measureTimeMillis { demo() }}")
    }
}

fun blockingCall(durationMs: Long) {
    Thread.sleep(durationMs)
}

fun javaAsyncCall(durationMs: Long): Future<*> {
    // note: you'd never actually create a thread pool per call. just doing for conciseness.
    return Executors.newFixedThreadPool(1).submit { Thread.sleep(durationMs) }!!
}

suspend fun suspendingCall(durationMs: Long) {
    delay(durationMs)
}

/**
 * Simulates a server serving requests in a coroutine context 'sharedContext', and another code path that performs
 * blocking I/O on 'sharedContext'. This clogs up 'sharedContext' and halts request handling.
 *
 * examples of this in storefront-service:
 * - blocking CRDB Jdbi calls being made in the 'ServiceCoroutineContext'.
 * - blocking http clients (Salesforce, Pactsafe, SearchService, PaymentService) in 'RepositoryCoroutineContext'
 */
suspend fun sharedContextDemo() {
    val nThreads = 5
    val sharedContext = Executors.newFixedThreadPool(nThreads).asCoroutineDispatcher()

    // requests being handled on a coroutine in the shared context. this should never block!
    val serverJob = CoroutineScope(sharedContext).launch {
        while (true) {
            val elapsed = measureTimeMillis {
                // simulate work of handling a request
                delay(1000L)
            }
            println("handled request in $elapsed ms!")
        }
    }

    // wait a bit
    delay(5000L)

    // blocking API calls clog up the thread pool - this halts request handling!
    (1..nThreads).map {
        CoroutineScope(sharedContext).async {
            println("making blocking call #$it")
            blockingCall(5000L)
            println("finished blocking call #$it")
        }
    }.awaitAll()
    println("done with all blocking calls!")

    // ** request handling resumes once thread pool frees up **

    delay(5000L)
    serverJob.cancel()
}

suspend fun timeoutDemo() {
    println("starting badTimeoutDemo...(should take ~1000ms)")
    val t1 = measureTimeMillis { badTimeoutDemo() }
    println("finished badTimeoutDemo (took $t1)")
    delay(1000L)

    println("starting goodBlockingTimeoutDemo...(should take ~100ms)")
    val t2 = measureTimeMillis { goodBlockingTimeoutDemo() }
    println("finished goodBlockingTimeoutDemo (took $t2)")
    delay(1000L)

    println("starting goodSuspendingTimeoutDemo...(should take ~100ms)")
    val t3 = measureTimeMillis { goodSuspendingTimeoutDemo() }
    println("finished goodSuspendingTimeoutDemo (took $t3)")
}

/**
 * Illustrates that `withTimeout` can't cancel thread-blocking code.
 */
suspend fun badTimeoutDemo() {
    var callCanceledCorrectly = true
    try {
        withTimeout(100L) {
            withContext(Dispatchers.IO) {
                blockingCall(durationMs = 1000L)
                callCanceledCorrectly = false
            }
        }
    } catch (e: Exception) {
        val msg = if (callCanceledCorrectly) "call was cancelled correctly" else "call wasn't cancelled correctly"
        println(msg)
    }
}

/**
 * Illustrates that Futures w/ timeouts do correctly cancel thread-blocking code.
 */
suspend fun goodBlockingTimeoutDemo() {
    var callCanceledCorrectly = true
    try {
        withContext(Dispatchers.IO) {
            javaAsyncCall(durationMs = 1000L).get(100L, TimeUnit.MILLISECONDS)
            callCanceledCorrectly = false
        }
    }
    catch (e: Exception) {
        val msg = if (callCanceledCorrectly) "call was cancelled correctly" else "call wasn't cancelled correctly"
        println(msg)
    }
}

/**
 * Illustrates that `withTimeout` works on suspending functions that don't block the underlying thread.
 */
suspend fun goodSuspendingTimeoutDemo() {
    var callCanceledCorrectly = true
    try {
        withTimeout(100L) {
            withContext(Dispatchers.IO) {
                suspendingCall(durationMs = 1000L)
                callCanceledCorrectly = false
            }
        }
    } catch (e: Exception) {
        val msg = if (callCanceledCorrectly) "call was cancelled correctly" else "call wasn't cancelled correctly"
        println(msg)
    }
}