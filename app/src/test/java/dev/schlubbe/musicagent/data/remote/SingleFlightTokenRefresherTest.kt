package dev.schlubbe.musicagent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SingleFlightTokenRefresherTest {

    @Test fun `concurrent 401s log in once and all get the new token`() {
        val token = AtomicReference<String?>("old")
        val logins = AtomicInteger()
        val refresher = SingleFlightTokenRefresher(
            currentToken = { token.get() },
            login = {
                logins.incrementAndGet()
                Thread.sleep(50)
                token.set("new")
                true
            },
        )
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val results = (1..threads).map {
            pool.submit<String?> {
                start.await()
                refresher.freshToken("old")
            }
        }
        start.countDown()
        val tokens = results.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, logins.get())
        assertEquals(List(threads) { "new" }, tokens)
    }

    @Test fun `a request that failed with an outdated token reuses the current one`() {
        val logins = AtomicInteger()
        val refresher = SingleFlightTokenRefresher(
            currentToken = { "new" },
            login = { logins.incrementAndGet(); true },
        )
        assertEquals("new", refresher.freshToken("old"))
        assertEquals(0, logins.get())
    }

    @Test fun `a failed login is not retried until the backoff has passed`() {
        var now = 1_000_000L
        val logins = AtomicInteger()
        val refresher = SingleFlightTokenRefresher(
            currentToken = { "old" },
            login = { logins.incrementAndGet(); false },
            failureBackoffMs = 10_000L,
            clock = { now },
        )
        assertNull(refresher.freshToken("old"))
        assertNull(refresher.freshToken("old"))
        assertEquals(1, logins.get())

        now += 10_000L
        assertNull(refresher.freshToken("old"))
        assertEquals(2, logins.get())
    }
}
