package mmo.minigames.kuyo

/*
import com.soywiz.klock.*
import com.soywiz.korge.bitmapfont.*
import com.soywiz.korge.input.*
import com.soywiz.korge.render.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*

class KuyoScene(val seed: Long = Klock.currentTimeMillis()) : Scene() {
    lateinit var board: KuyoBoardView

    override suspend fun sceneInit(sceneView: Container) {

        println("KuyoScene.init()")
        val rand1 = MtRand(seed)
        val rand2 = MtRand(seed)
        val font1 = resourcesRoot["font1.fnt"].readBitmapFont(ag)

        val tiles = KuyoTiles(resourcesRoot["puyo.png"].readTexture(ag))
        val bg = resourcesRoot["bg.png"].readTexture(ag)
        root += views.image(bg)
        root.gamepad {
            connection {
                println("connection: $it")
            }
        }
        board = KuyoBoardView(0, KuyoBoard(), tiles, font1, rand = rand1).also { it.position(64, 64); root += it }
        //KuyoBoardView(1, KuyoBoard(), tiles, font1, rand = rand2).also { it.position(640, 64); root += it }
    }
}

class KuyoTiles(val tex: Texture) {
    val tiles = Array2(16, 16, tex)

    //val tile = tex.sliceSize(0, 0, 32, 32)
    //val tile = arrayListOf<SceneTexture>()
    init {
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                tiles[x, y] = tex.slice(x * 32, y * 32, 32, 32)
            }
        }
    }

    fun getTex(
        color: Int,
        up: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        down: Boolean = false
    ): Texture {
        if (color <= 0) return tiles[5, 15]
        val combinable = true
        //val combinable = false
        var offset = 0
        if (combinable) {
            offset += if (down) 1 else 0
            offset += if (up) 2 else 0
            offset += if (right) 4 else 0
            offset += if (left) 8 else 0
        }
        return tiles[offset, color - 1]
    }

    fun getDestroyTex(color: Int): Texture {
        return when (color) {
            1 -> tiles[0, 12]
            2 -> tiles[0, 13]
            3 -> tiles[2, 12]
            4 -> tiles[2, 13]
            5 -> tiles[4, 12]
            else -> tiles[5, 15]
        }
    }

    fun getHintTex(color: Int): Texture {
        return tiles[5 + color - 1, 11]
    }

    val default = getTex(1)
    val colors = listOf(tiles[0, 0]) + (0 until 5).map { getTex(it + 1) }
    val empty = getTex(0)
}

val DEFAULT_EASING = Easing.LINEAR

class KuyoDropView(val board: KuyoBoardView, private var model: KuyoDrop) : ViewContainer() {
    val dropModel get() = model
    val boardModel get() = board.model
    val views = (0 until 4).map { ViewKuyo(IPoint(0, 0), 0, board).also { addChild(it) } }
    val queue get() = board.queue

    fun rotateRight() {
        set(model.rotateOrHold(+1, board.model), time = 0.1)
    }

    fun rotateLeft() {
        set(model.rotateOrHold(-1, board.model), time = 0.1)
    }

    fun moveBy(delta: IPoint, easing: Easing = DEFAULT_EASING): Boolean {
        val newModel = model.tryMove(delta, board.model) ?: return false
        set(newModel, time = if (delta.x == 0) 0.05 else 0.1, easing = easing)
        return true
    }

    fun setImmediate(newDrop: KuyoDrop) {
        model = newDrop
        for ((index, item) in newDrop.transformedItems.withIndex()) {
            val view = views[index]
            view.set(item.color)
            view.moveToImmediate(item.pos)
        }
        setHints()
    }

    fun setHints() {
        board.setHints(boardModel.place(model).dst.gravity().transforms.map { it.cdst })
    }

    fun set(newDrop: KuyoDrop, time: Double = 0.1, easing: Easing = DEFAULT_EASING) {
        queue.discard {
            model = newDrop

            setHints()

            parallel {
                for ((index, item) in newDrop.transformedItems.withIndex()) {
                    val view = views[index]
                    view.set(item.color)
                    sequence {
                        view.moveTo(item.pos, time = time, easing = easing)
                    }
                }
            }
        }
    }

    fun place() {
        queue.discard {
            //println("------")
            alpha = 0.0
            val placement = listOf(IPoint(0, 0), IPoint(0, 1), IPoint(0, 2), IPoint(0, 3)).firstNotNullOrNull {
                dropModel.tryMove(it, boardModel)?.let { if (boardModel.canPlace(it)) it else null }
            } ?: error("GAME OVER!")
            board.applyStep(boardModel.place(placement))
            var chains = 1
            do {
                board.applyStep(boardModel.gravity())
                val explosions = boardModel.explode()
                board.applyStep(explosions)
                if (explosions.transforms.isNotEmpty()) {
                    launch { board.chain(chains) }
                    chains++
                }
            } while (explosions.transforms.isNotEmpty())
            alpha = 1.0
            setImmediate(KuyoDrop(IPoint(2, 0), board.generateRandShape()))
            queue.discard()
        }
    }

    fun moveLeft() {
        moveBy(IPoint(-1, 0))
    }

    fun moveRight() {
        moveBy(IPoint(+1, 0))
    }

    fun moveDownOrPlace() {
        downTimer.reset()
        if (!moveBy(IPoint(0, +1), easing = Easing.LINEAR)) {
            place()
        }
    }

    lateinit var downTimer: TimerComponent

    init {
        setImmediate(model)
        val view: View
        view.keys {
            down(Key.RIGHT) { moveRight() }
            down(Key.LEFT) { moveLeft() }
            down(Key.DOWN) { moveDownOrPlace() }
            down(Key.Z) { rotateLeft() }
            down(Key.X) { rotateRight() }
            down(Key.ENTER) { rotateRight() }
            //down(Key.UP) { moveBy(IPoint(0, -1)) }                // Only debug
            //down(Key.SPACE) { place() }                // Only debug
        }
        gamepad {
            stick(board.playerId, GameStick.LEFT) { x, y ->
                if (queue.size < 1) {
                    when {
                        x < -0.25 -> moveLeft()
                        x > +0.25 -> moveRight()
                    }
                }
                if (y < -0.25) moveDownOrPlace()
            }
            down(board.playerId, GamepadButton.LEFT) {
                moveLeft()
            }
            down(board.playerId, GamepadButton.RIGHT) {
                moveRight()
            }
            down(board.playerId, GamepadButton.BUTTON2) {
                rotateRight()
            }
            down(board.playerId, GamepadButton.BUTTON1) {
                rotateLeft()
            }
        }
        downTimer = timer(1.0) {
            moveDownOrPlace()
        }
    }
}

val NCOLORS = 4

class KuyoBoardView(
    val playerId: Int,
    var model: KuyoBoard,
    val tiles: KuyoTiles,
    val font: BitmapFont,
    val rand: Rand = Rand.DEFAULT
) : ViewContainer() {
    val width get() = model.width
    val height get() = model.height
    val queue = JobQueue()
    val hints = ViewContainer().also { this += it }
    val kuyos = Array2<ViewKuyo?>(model.width, model.height) { x, y ->
        //ViewKuyo(IPoint(x, y), 0, this@KuyoBoardView).apply { this@KuyoBoardView.addChild(this) }
        null
    }

    fun generateRandShape() = KuyoShape2(rand(1..NCOLORS), rand(1..NCOLORS))

    val dropping = KuyoDropView(this, KuyoDrop(IPoint(2, 0), generateRandShape())).apply {
        this@KuyoBoardView.addChild(this)
    }


    fun updateTexs() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = IPoint(x, y)
                val kuyo = kuyos[p]
                if (kuyo != null) {
                    val c = model[p]
                    val left = model[IPoint(x - 1, y)] == c
                    val right = model[IPoint(x + 1, y)] == c
                    val up = model[IPoint(x, y - 1)] == c
                    val down = model[IPoint(x, y + 1)] == c
                    kuyo.tex = tiles.getTex(c, up, left, right, down)
                }
            }
        }
    }

    suspend fun chain(count: Int) {
        val text = Text(font, "$count chains!", 32).also {
            this += it
            it.position(16, 96)
        }
        text.show(time = 0.3, easing = Easing.QUADRATIC_EASE_IN_OUT)
        parallel {
            sequence { text.moveBy(0.0, -64.0, time = 0.3) }
            sequence { text.hide(time = 0.3) }
        }
        text.removeFromParent()
    }

    fun setHints(points: List<ColoredPoint>) {
        hints.removeChildren()
        for (p in points) {
            hints += Image(tiles.getHintTex(p.color)).also {
                it.x = p.pos.x * 32.0 + 8.0
                it.y = p.pos.y * 32.0 + 8.0
                it.alpha = 0.6
                it.scale = 0.75
            }
        }
    }

    suspend fun applyStep(step: KuyoStep<out KuyoTransform>) {
        model = step.dst
        //println(model.toBoardString())

        parallel {
            for (transform in step.transforms) {
                //println("TRANSFORM : $transform")
                when (transform) {
                    is KuyoTransform.Place -> {
                        val item = transform.item
                        //println("PLACED: $item")
                        sequence {
                            kuyos[item.pos] = ViewKuyo(item.pos, item.color, this@KuyoBoardView).also {
                                it.alpha = 1.0; addChild(it)
                            }
                        }
                    }
                    is KuyoTransform.Move -> {
                        val kuyo = kuyos[transform.src]
                        kuyos[transform.src] = null
                        kuyos[transform.dst] = kuyo
                        sequence {
                            kuyo?.moveTo(transform.dst, time = 0.3, easing = Easing.QUADRATIC_EASE_IN)
                        }
                    }
                    is KuyoTransform.Explode -> {
                        for (item in transform.items) {
                            val kuyo = kuyos[item] ?: continue
                            sequence {
                                kuyo.delay(0.1)
                                kuyo.tex = tiles.getDestroyTex(kuyo.color)
                                kuyo.tween(kuyo::scale[1.5], time = 0.3, easing = Easing.QUADRATIC_EASE_IN)
                                val destroyEasing = Easing.QUADRATIC_EASE_OUT
                                parallel {
                                    sequence {
                                        kuyo.tween(kuyo::scale[0.5], time = 0.1, easing = destroyEasing)
                                        kuyo.tween(kuyo::scaleY[0.1], time = 0.1, easing = QUADRATIC_EASE_OUT)
                                    }
                                    sequence {
                                        kuyo.hide(time = 0.15, easing = destroyEasing)
                                        //kuyo.delay(time = 0.2)
                                    }
                                }
                                kuyo.removeFromParent()
                            }
                        }
                    }
                    else -> {
                        println("Unhandled transform : $transform")
                    }
                }
            }
        }
        updateTexs()
    }
}

class ViewKuyo(var pos: IPoint, var color: Int, val board: KuyoBoardView) : Image(board.tiles.empty) {
    val tiles get() = board.tiles

    init {
        anchor(0.5, 0.5)
        set(color)
        moveToImmediate(pos)
        mouse {
            click {
                println("kuyo $pos!")
            }
        }
    }

    fun getRPos(pos: IPoint) = (pos * IPoint(32, 32)) + IPoint(16, 16)

    suspend fun moveTo(npos: IPoint, time: Double = 0.1, easing: Easing = DEFAULT_EASING) {
        pos = npos
        val screenPos = getRPos(npos)
        moveTo(screenPos.x.toDouble(), screenPos.y.toDouble(), time = time, easing = easing)
    }

    fun moveToImmediate(npos: IPoint) {
        pos = npos
        val screenPos = getRPos(pos)
        x = screenPos.x.toDouble()
        y = screenPos.y.toDouble()
    }

    fun set(value: Int) {
        this.color = value
        tex = when (value) {
            0 -> tiles.empty
            in 0 until 5 -> tiles.colors[value]
            else -> tiles.default
        }
    }
}
*/
