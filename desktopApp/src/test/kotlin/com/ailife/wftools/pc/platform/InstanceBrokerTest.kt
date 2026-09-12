package com.ailife.wftools.pc.platform

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstanceBrokerTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `second launcher forwards unicode paths to primary without another instance`()=runBlocking {
        val directory=temp.newFolder().toPath()
        val primary=InstanceBroker.openOrForward(emptyList(),directory)!!
        try {
            assertTrue(withTimeout(2000){primary.requests.receive()}.isEmpty())
            val file=directory.resolve("中文 含空格.watch")
            assertNull(InstanceBroker.openOrForward(listOf(file),directory))
            assertEquals(listOf(file),withTimeout(2000){primary.requests.receive()})
        }finally{primary.close()}
        InstanceBroker.openOrForward(emptyList(),directory)!!.close()
    }
}
