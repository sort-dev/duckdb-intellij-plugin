package dev.sort.duckdb.sql

import com.intellij.util.text.VersionComparatorUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** IntelliJ uses its own comparator, not SemVer's prerelease/build-metadata rules. */
class DuckdbDevVersionTest {
    private val dev = "0.4.0-dev.202610132007+661798ef63e4"

    @Test fun gitDevVersionsOrderBetweenDifferentReleaseBases() {
        assertTrue(VersionComparatorUtil.compare(dev, "0.3.5") > 0)
        assertTrue(VersionComparatorUtil.compare(dev, "0.4.1") < 0)
        assertTrue(VersionComparatorUtil.compare(dev, "0.4.0-dev.202610132006+661798ef63e4") > 0)
        assertEquals(0, VersionComparatorUtil.compare(dev, dev))
    }

    @Test fun sameBaseReleaseNeedsManualDowngradeFromRequestedDevFormat() {
        assertTrue(VersionComparatorUtil.compare(dev, "0.4.0") > 0)
        assertTrue(VersionComparatorUtil.compare("$dev.dirty", dev) > 0)
    }
}
