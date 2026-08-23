package postmark.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue

class ApiStackIntegrationTest {
    @Test
    fun liveStackHealthCheckReturnsAvailable() {
        val raw = System.getenv("POSTMARK_TEST_API_BASE_URL")
        assumeTrue(raw != null, "POSTMARK_TEST_API_BASE_URL is not set")
        runTest {
            val repository = HealthRepository.create(ApiBaseUrl.parse(checkNotNull(raw)))
            assertEquals(HealthCheckResult.Available, repository.check())
        }
    }
}
