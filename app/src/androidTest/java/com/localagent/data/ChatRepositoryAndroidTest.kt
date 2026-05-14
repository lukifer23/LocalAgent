package com.localagent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localagent.bridge.BridgeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRepositoryAndroidTest {

    private lateinit var db: LocalAgentDatabase

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db =
            Room.inMemoryDatabaseBuilder(ctx, LocalAgentDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun routesUserMessageByExplicitSessionId() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val repo = ChatRepository(db, scope)
            repo.handleEvent(BridgeEvent.Session(ts = 1L, id = "a", title = null))
            repo.selectSession("b")
            repo.handleEvent(BridgeEvent.UserMessage(ts = 2L, text = "z", sessionId = "a"))
            repo.selectSession("a")
            val msgs = repo.activeMessages.first()
            assertEquals(1, msgs.size)
            assertEquals("z", msgs[0].body)
        }
}
