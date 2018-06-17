package mmo.server.script

import com.soywiz.klock.*
import com.soywiz.korma.geom.*
import mmo.server.*

class Princess(scene: ServerScene) : Npc() {
    val levers = (0 until 8).map { Lever(scene, it, 64 + 32 * it, 128) }
    val WEST = false//Lever.OFF
    val EAST = true//Lever.ON

    //enum class Count { LESS, MORE }
    //val LESS = Count.LESS
    //val MORE = Count.MORE

    //val leversRule = listOf(/*WEST this once forces the rest*/LESS, MORE, LESS, MORE, LESS, MORE, LESS, MORE)
    //val leversDirection = leversRule.withIndex().map { (index, rule) ->
    //    val firstHalf = index < levers.size / 2
    //    val more = rule == MORE
    //    val less = !more
    //    when {
    //        firstHalf && more -> EAST
    //        firstHalf && less -> WEST
    //        !firstHalf && more -> WEST
    //        !firstHalf && less -> EAST
    //        else -> invalidOp
    //    }
    //}

    val expectedLeversDirection = listOf(WEST, EAST, WEST, EAST, EAST, WEST, EAST, WEST)
    val actualLeversDirection get() = levers.map { it.on }

    val leversInPosition get() = expectedLeversDirection == actualLeversDirection

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
            val people = container?.users?.size ?: 0
            say("Will someone else come?\nWe are already %d!", people + 1)
            wait(1.seconds)
        }
    }

    override suspend fun onUserInterfaction(user: User) {
        val gold = "gold"
        val experience = "experience"
        lookAt(user)
        conversationWith(user) {
            mood("happy")
            say("Hello!")
            options<Unit>("What do you want?") {
                option("Where am I?") {
                    say("You are in a proof of concept of a MMO fully written in kotlin!")
                    say("I am written using Kotlin coroutines inside [Ktor](https://ktor.io/).")
                    close()
                }
                option("Give me some money!") {
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
                }
                if (!leversInPosition) {
                    //println("expectedLeversDirection=$expectedLeversDirection")
                    //println("actualLeversDirection=$actualLeversDirection")
                    option("What are those 8 levers?") {
                        say(
                            """
                            There are eight soldiers marching to
                            the battle waiting for them in the west.
                        """.trimIndent()
                        )
                        say(
                            """
                            The soldier in the front, turns to the rest,
                            and makes them a strange request:
                        """.trimIndent()
                        )
                        say(
                            """
                            Half of us, should turn where most of the others
                            are, while the other half should do the opposite.
                        """.trimIndent()
                        )
                        say(
                            """
                            But you can't do the same as the mates that are
                            next to you.
                        """.trimIndent()
                        )
                        say(
                            """
                            Then the soldier in the front, turned,
                            and stood up gazing at the battle.
                        """.trimIndent()
                        )
                        say(
                            """
                            What strange story, huh?
                        """.trimIndent()
                        )
                        close()
                    }
                } else if (!user.getFlag("princess-levers")) {
                    option("The soldiers are ready to the battle, miss!") {
                        user.doOnce(flag = "princess-levers") {
                            user.addItems(gold, amount = 5000)
                            user.addItems(experience, amount = 5000)
                        }
                        say("Good job!")
                        close()
                    }
                }

                option("Nothing, I'm ok!") {
                    close()
                }
            }
        }
    }
}
