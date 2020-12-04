package mmo.client

import com.soywiz.klock.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korio.async.*
import com.soywiz.korio.async.delay
import com.soywiz.korma.geom.*
import kotlinx.coroutines.*
import mmo.protocol.*
import mmo.shared.*
import kotlin.coroutines.*
import kotlin.math.*

class ClientEntity(
	val resourceManager: ResourceManager,
	val coroutineContext: CoroutineContext,
	val id: Long,
	val views: Views,
	val listener: ClientListener
) {
	// @TODO: Remove code duplication related to layers
	val imageBody = Image(Bitmaps.transparent).apply {
		anchorX = 0.5
		anchorY = 1.0
	}
	val imageArmor = Image(Bitmaps.transparent).apply {
		anchorX = 0.5
		anchorY = 1.0
	}
	val imageHead = Image(Bitmaps.transparent).apply {
		anchorX = 0.5
		anchorY = 1.0
	}
	val imageHair = Image(Bitmaps.transparent).apply {
		anchorX = 0.5
		anchorY = 1.0
	}
	val quest = Image(Bitmaps.transparent).apply {
		anchorY = 2.3
		anchorX = 0.5
	}
	val bubbleChatBg = NinePatchEx(resourceManager.bubbleChat, width = 64.0, height = 64.0).disableMouse().alpha(0.75)
	val bubbleChatText = TextOld("", textSize = 8.0, font = resourceManager.font).apply {
		format = format.copy(color = Colors.BLACK)
	}
	val bubbleChat = Container().apply {
		this += bubbleChatBg
		this += bubbleChatText
		visible = false
	}
	val rview = Container().apply {
		name = "entity$id"
		addChild(imageBody)
		addChild(imageArmor)
		addChild(imageHead)
		addChild(imageHair)
		addChild(quest)
	}
	val view = Container().apply {
		name = "entity$id"
		addChild(rview)
		addChild(bubbleChat)
	}
	var skinBody: CharacterSkin = resourceManager.emptySkin
	var skinArmor: CharacterSkin = resourceManager.emptySkin
	var skinHead: CharacterSkin = resourceManager.emptySkin
	var skinHair: CharacterSkin = resourceManager.emptySkin

	fun setSkin(body: Skins.Body, armor: Skins.Armor, head: Skins.Head, hair: Skins.Hair) {
		launchImmediately(coroutineContext) {
			imageBody.bitmap = resourceManager.getSkin(Skins.Body.prefix, body.fileName).apply { skinBody = this }[direction.id, 0]
			imageArmor.bitmap = resourceManager.getSkin(Skins.Armor.prefix, armor.fileName).apply { skinArmor = this }[direction.id, 0]
			imageHead.bitmap = resourceManager.getSkin(Skins.Head.prefix, head.fileName).apply { skinHead = this }[direction.id, 0]
			imageHair.bitmap = resourceManager.getSkin(Skins.Hair.prefix, hair.fileName).apply { skinHair = this }[direction.id, 0]
		}
	}

	fun setPos(x: Double, y: Double) {
		moving?.cancel()
		moving = null
		view.x = x
		view.y = y
		listener.updatedEntityCoords(this)
	}

	var moving: Deferred<Unit>? = null
	var direction = CharDirection.DOWN

	fun setSkinTex(dir: Int, frame: Int) {
		imageBody.bitmap = skinBody[dir, frame]
		imageArmor.bitmap = skinArmor[dir, frame]
		imageHead.bitmap = skinHead[dir, frame]
		imageHair.bitmap = skinHair[dir, frame]
	}

	fun move(src: Point, dst: Point, totalTime: TimeSpan) {
		moving?.cancel()
		view.x = src.x
		view.y = src.y
		moving = async(coroutineContext) {
			//println("move $src, $dst, time=$totalTime")
			val dx = (dst.x - src.x).absoluteValue
			val dy = (dst.y - src.y).absoluteValue
			val horizontal = dx >= dy
			val direction = when {
				horizontal && dst.x >= src.x -> CharDirection.RIGHT
				horizontal && dst.x < src.x -> CharDirection.LEFT
				!horizontal && dst.y < src.y -> CharDirection.UP
				!horizontal && dst.y >= src.y -> CharDirection.DOWN
				else -> CharDirection.RIGHT
			}
			view.tween(view::x[src.x, dst.x], view::y[src.y, dst.y], time = totalTime) { step ->
				val elapsed = totalTime.seconds * step
				val frame = (elapsed / 0.1).toInt()
				setSkinTex(direction.id, frame % CharacterSkin.COLS)
				listener.updatedEntityCoords(this@ClientEntity)
			}
			setSkinTex(direction.id, 1)
		}
	}

	fun lookAt(direction: CharDirection) {
		this.direction = direction
		//println("lookAt.DIRECTION[$this]: $direction")
		setSkinTex(direction.id, 0)
	}

	var sayPromise: Deferred<Unit>? = null
	fun say(text: String) {
		sayPromise?.cancel()
		this.bubbleChatText.text = text
		// @TODO: Do not hardcode constants here, let's get from 9-patch content info
		bubbleChatBg.width = this.bubbleChatText.width + 16
		bubbleChatBg.height = this.bubbleChatText.height * 2 + 16
		//println("bubbleChatBg.localMatrix=${bubbleChatBg.localMatrix}")
		bubbleChat.visible = true
		bubbleChatText.x = 8.0
		bubbleChatText.y = 8.0
		bubbleChat.y = -bubbleChatBg.height - imageBody.height + 32
		bubbleChat.x -imageBody.width / 2 - 24.0
		sayPromise = async(coroutineContext) {
			delay(2000)
			this@ClientEntity.bubbleChatText.text = ""
			bubbleChat.visible = false
		}
	}

	fun setQuestSatus(status: QuestStatus) {
		//views.ninePatch()
		println("QuestStatus = $status")
		launchImmediately(coroutineContext) {
			quest.bitmap = when (status) {
				QuestStatus.NONE -> resourceManager.getBmpSlice("")
				QuestStatus.NEW -> resourceManager.getBmpSlice("quest-availiable.png")
				QuestStatus.UNCOMPLETE -> resourceManager.getBmpSlice("quest.png")
				QuestStatus.COMPLETE -> resourceManager.getBmpSlice("quest-ready.png")
			}
		}
	}
}
