package com.htc.vive.eagle.hackathon.starter.oria.recording

import org.junit.Assert.*
import org.junit.Test

class OriaLabDiskBudgetTest {
    private val mib = 1024L * 1024
    private val reserve = 512 * mib

    @Test fun largeCaptureHasNoFixedPerSessionOrTotalByteQuota() {
        var free = 8L * 1024 * mib
        val budget = OriaLabDiskBudget(reserve, { free })
        repeat(8) {
            budget.reserveWrite((250 * mib).toInt())
            free -= 250 * mib
        }
        assertEquals("2,000 MiB of writes remain allowed with actual disk space available", free, budget.availableBytes)
    }

    @Test fun insufficientSpacePreservesTheRequestedReserve() {
        var free = reserve + 100
        val budget = OriaLabDiskBudget(reserve, { free })
        budget.reserveWrite(100)
        free -= 100
        assertEquals(reserve, budget.availableBytes)
        assertThrows(OriaLabStorageFullException::class.java) { budget.reserveWrite(1) }
        assertEquals(reserve, budget.availableBytes)
    }

    @Test fun smallWritesReuseConservativeBudgetInsteadOfQueryingEveryChunk() {
        var queries = 0
        val budget = OriaLabDiskBudget(reserve, { queries++; reserve + 100 * mib })
        budget.reserveWrite(0)
        repeat(100) { budget.reserveWrite(1024) }
        assertEquals(1, queries)
        budget.reserveWrite(mib.toInt())
        assertEquals(2, queries)
    }

    @Test fun aNewProbeNoticesSpaceUsedByOtherApplications() {
        var free = reserve + 100 * mib
        val budget = OriaLabDiskBudget(reserve, { free })
        budget.reserveWrite(256)
        free = reserve - 1
        assertThrows(OriaLabStorageFullException::class.java) { budget.reserveWrite(mib.toInt()) }
        assertEquals(free, budget.availableBytes)
    }
}
