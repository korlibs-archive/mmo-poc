package mmo.server.script

import com.soywiz.klock.*
import com.soywiz.korma.geom.*
import mmo.server.*

class Princess() : Npc() {
    init {
        src = Point2d(0, 50)
        skin = "princess1"
        name = "Princess"
    }

    override suspend fun script() {
        while (true) {
            moveTo(100, 50)
            wait(0.5.seconds)
            moveTo(0, 50)
            val people = container?.entities?.size ?: 0
            say("Will someone else come?\nWe are already $people!")
            wait(1.seconds)
        }
    }

    override suspend fun onUserInterfaction(user: User) {
        lookAt(user)
        conversationWith(user) {
            mood("happy")
            say("Hello!")
            options("What do you want?",
                NpcConversation.Option("Where am I?") {
                    say("You are in a proof of concept of a MMO fully written in kotlin!")
                    say("I am written using Kotlin coroutines inside [Ktor](https://ktor.io/).")
                    close()
                },
                NpcConversation.Option("Goodbye") {
                    say("Goodbye")
                    close()
                }
            )
        }
    }
}
