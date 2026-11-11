package app.invilink.xboard.panels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityStatusTest {
    @Test fun validatedNetworkDoesNotDependOnExternalProbe() {
        assertTrue(connectivityStatus(hasInternet = true, validated = true, probeSucceeded = false))
    }

    @Test fun unvalidatedNetworkCanBeConfirmedByProbe() {
        assertTrue(connectivityStatus(hasInternet = true, validated = false, probeSucceeded = true))
        assertFalse(connectivityStatus(hasInternet = true, validated = false, probeSucceeded = false))
    }

    @Test fun networkWithoutInternetCapabilityIsOffline() {
        assertFalse(connectivityStatus(hasInternet = false, validated = true, probeSucceeded = true))
    }
}
