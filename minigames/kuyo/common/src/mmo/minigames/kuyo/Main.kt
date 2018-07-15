package mmo.minigames.kuyo

/*
import com.soywiz.korge.scene.*
import com.soywiz.korge.view.*
import com.soywiz.korinject.*
import com.soywiz.korio.*
import com.soywiz.korma.geom.*

object Kuyo {
    val WINDOW_CONFIG = WindowConfig(
        width = (1280 * 0.7).toInt(),
        height = (720 * 0.7).toInt(),
        title = "Kuyo!"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        SceneApplication(WINDOW_CONFIG) { KuyoScene() }
    }

    object MainMenu {
        @JvmStatic
        fun main(args: Array<String>) {
            SceneApplication(WINDOW_CONFIG) { MainMenuScene() }
        }
    }

    object SkinTester {
        @JvmStatic
        fun main(args: Array<String>) {
            SceneApplication(WINDOW_CONFIG) {
                object : Scene() {
                    override suspend fun init(injector: AsyncInjector) {
                        val tiles = KuyoTiles(texture("puyo.png"))
                        val board = board(
                            "1.11.111...........",
                            ".............1.....",
                            "1.11.11.11..111....",
                            "1.1...1.11...1.....",
                            "...................",
                            "1.1...1.111.111....",
                            "1.11.11.1.1.111....",
                            "1.......111.111...."
                        )
                        for (y in 0 until board.height) {
                            for (x in 0 until board.width) {
                                val c = board[Point(x, y)]
                                val up = board[Point(x, y - 1)] == c
                                val down = board[Point(x, y + 1)] == c
                                val left = board[Point(x - 1, y)] == c
                                val right = board[Point(x + 1, y)] == c
                                if (c != 0) {
                                    Image(tiles.getTex(c, up, left, right, down)).also {
                                        it.position(x * 32.0, y * 32.0)
                                        root += it
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
*/
