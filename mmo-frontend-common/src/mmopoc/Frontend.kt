package mmopoc

import com.soywiz.korge.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.view.*
import com.soywiz.korim.color.*
import com.soywiz.korinject.*

fun main(args: Array<String>) = Korge(MmoModule())

open class MmoModule : Module() {
    override val mainScene = MainScene::class
    override suspend fun init(injector: AsyncInjector) {
        injector.mapPrototype { MainScene() }
    }
}

class MainScene : Scene() {
    override suspend fun sceneInit(sceneView: Container) {
        sceneView.addChild(views.solidRect(100, 100, Colors.RED))
    }
}