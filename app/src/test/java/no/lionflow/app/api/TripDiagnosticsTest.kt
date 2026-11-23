package no.lionflow.app.api

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripDiagnosticsTest {
    @Test fun logsAggregateCountsWithoutIdentifiersOrPayload() = runBlocking {
        val logs = mutableListOf<String>()
        TripHistory.load(Instant.parse("2026-10-06T00:00:00Z").toEpochMilli(), { logs.add(it) }) { _, cursor ->
            if (cursor == null) JSONObject("""
                {"_embedded":{"trips":[
                    {"id":"private-id","startedAt":"2026-10-05T01:02:03Z","distance":2.5,"latitude":45.12345},
                    {"id":"private-id","startedAt":"invalid-private-string","distance":-1,"createdAt":"2026-10-06T09:08:07Z"},
                    {"id":null,"stoppedAt":"invalid-private-string"},
                    null
                ]},"_links":{"next":{"href":"/private-url?pageToken=private-cursor"}}}
            """) else JSONObject()
        }
        val text = logs.joinToString("\n")
        assertTrue(text.contains("raw=4 objects=3 nonObjects=1 validStarts=2 noValidStart=1 blankIds=1"))
        assertTrue(text.contains("invalidDistances=2 validTrips=1"))
        assertTrue(text.contains("duplicateIds=1 duplicateAnonymous=0 droppedUnidentifiable=1"))
        assertTrue(text.contains("missingStartedAt=1 invalidStartedAt=1 invalidCreatedAt=0 invalidStoppedAt=1"))
        assertTrue(text.contains("recoveredFromCreatedAt=1 beforeRequest=1 firstUtc=2026-10-05 lastUtc=2026-10-06"))
        assertTrue(text.contains("stop=lastPage pages=2 retained=1"))
        for (value in listOf("private-", "45.12345", "01:02:03", "09:08:07")) {
            assertFalse(text.contains(value))
        }
    }
}
