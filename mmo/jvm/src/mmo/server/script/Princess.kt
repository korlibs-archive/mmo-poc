package mmo.server.script

import com.soywiz.klock.*
import com.soywiz.korma.geom.*
import mmo.server.*

class Princess(scene: ServerScene) : Npc() {
    val levers = (0 until 6).map { Lever(it, 64 + 32 * it, 128) }

    init {
        src = Point2d(0, 50)
        skin = "princess1"
        name = "Princess"
        scene.add(this)
        for (lever in levers) scene.add(lever)
    }

    override suspend fun script() {
        while (true) {
            moveTo(100, 50)
            moveBy(0, 20)
            wait(0.5.seconds)
            moveTo(0, 50)
            val people = container?.entities?.size ?: 0
            say("Will someone else come?\nWe are already %d!", people)
            wait(1.seconds)
        }
    }

    override suspend fun onUserInterfaction(user: User) {
        val gold = "gold"
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
                NpcConversation.Option("Give me some money!") {
                    if (user.getItemAmount(gold) >= 2000) {
                        mood("angry") {
                            say("*Sigh* You have enough money already!")
                        }
                        say("Goodbye!")
                    } else {
                        say("Sure, here we go!")
                        user.addItems(gold, amount = 1000)
                    }
                    close()
                },
                NpcConversation.Option("Nothing, I'm ok!") {
                    close()
                }
            )
        }
    }
}
