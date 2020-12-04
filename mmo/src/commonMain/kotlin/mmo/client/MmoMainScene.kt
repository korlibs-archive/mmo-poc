package mmo.client

import com.soywiz.klock.*
import com.soywiz.klogger.*
import com.soywiz.kmem.*
import com.soywiz.korge.component.docking.*
import com.soywiz.korge.input.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.tiled.*
import com.soywiz.korge.view.*
import com.soywiz.korge.view.onClick
import com.soywiz.korim.color.*
import com.soywiz.korim.color.interpolate
import com.soywiz.korio.async.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korio.util.i18n.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import mmo.protocol.*
import mmo.shared.*

class MmoMainScene(
	val resourceManager: ResourceManager,
	val browser: Browser,
	val module: Module
) : Scene(), ClientListener {
	val scope = this
	val MAP_SCALE = 3.0
	var ws: WebSocketClient? = null
	val entitiesById = LinkedHashMap<Long, ClientEntity>()
	val background by lazy { SolidRect(1280 / 3.0, 720 / 3.0, RGBA(0x1e, 0x28, 0x3c, 0xFF)) }
	val camera by lazy {
		Camera().apply {
			scale = MAP_SCALE
			sceneView += this
		}
	}
	val entityContainer by lazy {
		Container().apply {
			this += background
			camera += this
		}
	}
	val conversationOverlay by lazy {
		Container().apply {
			sceneView += this
		}
	}
	val conversationsById = LinkedHashMap<Long, ClientNpcConversation>()

	var userId: Long = -1L

	override fun updatedEntityCoords(entity: ClientEntity) {
		if (entity.id == userId) {
			//camera.setTo(entity.view)
			//println("CAMERA(${camera.x}, ${camera.y})")
			//println("USER MOVED TO $entity")
			camera.x =
				-(((entity.view.x - module.size.width.toDouble() / MAP_SCALE / 2) * MAP_SCALE).clamp(0.0, 5000.0))
			camera.y =
				-(((entity.view.y - module.size.height.toDouble() / MAP_SCALE / 2) * MAP_SCALE).clamp(0.0, 5000.0))
		} else {
			//println("OTHER MOVED TO $entity")
		}
	}

	private val COLOR_TRANSFORM_NPC_OVER = ColorTransform(1, 1, 1, 1, 32, 32, 32, 0)
	private val COLOR_TRANSFORM_NPC_OUT = ColorTransform()

	fun getOrCreateEntityById(id: Long): ClientEntity {
		return entitiesById.getOrPut(id) {
			ClientEntity(resourceManager, coroutineContext, id, views, this@MmoMainScene).apply {
				rview.onClick {
					launchImmediately {
						ws?.sendPacket(ClientRequestInteract(id))
					}
				}
				rview.onOver { rview.colorTransform = COLOR_TRANSFORM_NPC_OVER }
				rview.onOut { rview.colorTransform = COLOR_TRANSFORM_NPC_OUT }
				entityContainer.addChild(view)
				entitiesById[id] = this
			}
		}
	}

	suspend fun init() {
		try {
			ws = WebSocketClient((injector.getOrNull() ?: ServerEndPoint("ws://127.0.0.1:8080/ws/")).endpoint)
			val ws = ws!!
			Console.error("Language: ${Language.CURRENT}")
			ws.onError { it.printStackTrace() }
			ws.onClose {
				//enterDebugger()
				println("WS closed")
			}
			ws.sendPacket(ClientSetLang(Language.CURRENT.iso6391))
			ws.onStringMessage { str ->
				val packet = deserializePacket(str)

				//println("CLIENT RECEIVED: $packet")

				when (packet) {
					is EntityDisappear -> {
						val entity = entitiesById.remove(packet.entityId)
						entity?.view?.removeFromParent()
					}
					is EntityUpdates -> {
						val now = packet.currentTime
						for (update in packet.updates) {
							val entity = getOrCreateEntityById(update.entityId)

							entity.setSkin(
								Skins.Body[update.skin.body]!!,
								Skins.Armor[update.skin.armor]!!,
								Skins.Head[update.skin.head]!!,
								Skins.Hair[update.skin.hair]!!
							)
							entity.lookAt(update.direction)

							val elapsed = (now - update.srcTime).toDouble()
							val totalTime = (update.dstTime - update.srcTime).toDouble()

							//println("Update: $update")

							val src = tilePosToSpriteCoords(update.srcX, update.srcY)
							val dst = tilePosToSpriteCoords(update.dstX, update.dstY)
							val srcX = src.x
							val srcY = src.y
							val dstX = dst.x
							val dstY = dst.y
							if (totalTime > 0) {
								val ratio = (if (totalTime > 0.0) elapsed / totalTime else 1.0).clamp(0.0, 1.0)

								val currentX = ratio.interpolate(srcX, dstX)
								val currentY = ratio.interpolate(srcY, dstY)

								val remainingTime = totalTime - elapsed

								entity.move(
									Point(currentX, currentY),
									Point(dstX, dstY),
									remainingTime.milliseconds
								)
							} else {
								entity.setPos(dstX, dstY)
							}
						}
					}
					is EntitySay -> {
						val entity = entitiesById[packet.entityId]
						entity?.say(packet.text)
					}
					is ConversationStart -> {
						conversationsById[packet.id] =
							ClientNpcConversation(resourceManager, conversationOverlay, packet.npcId, packet.id, ws!!, scope)
					}
					is ConversationClose -> {
						conversationsById.remove(packet.id)
					}
					is ConversationMoodSet -> {
						val conversation = conversationsById[packet.id]
						conversation?.setMood(packet.mood)
					}
					is ConversationImage -> {
						val conversation = conversationsById[packet.id]
						conversation?.setImage(packet.image)
					}
					is ConversationOptions -> {
						val conversation = conversationsById[packet.id]
						conversation?.options(packet.text, packet.options)
					}
					is UserBagUpdate -> {
						bag[packet.item] = packet.amount
						bagUpdated()
					}
					is EntityLookDirection -> {
						val entity = entitiesById[packet.entityId]
						entity?.lookAt(packet.direction)
					}
					is Ping -> {
						launch(coroutineContext) {
							ws?.sendPacket(Pong(packet.pingTime))
						}
					}
					is Pong -> {
						latency = DateTime.nowUnixLong() - packet.pingTime
						launch(coroutineContext) {
							delay(5.seconds)
							sendPing()
						}
					}
					is UserSetId -> {
						userId = packet.entityId
						val user = entitiesById[userId]
						if (user != null) {
							this.updatedEntityCoords(user)
							//user.view.mouse.dettach()
							user.view.mouseEnabled = false
						}
					}
					is QuestUpdate -> {
						val entity = entitiesById[packet.entityId]
						entity?.setQuestSatus(packet.status)
					}
					else -> {
						Console.error("Unhandled packet from server", packet)
					}
				}
			}
			sendPing()
		} catch (e: Throwable) {
			e.printStackTrace()
		}
	}

	fun sendPing() {
		launch(coroutineContext) {
			ws?.sendPacket(Ping(DateTime.nowUnixLong()))
		}
	}

	var latency: Long = 0L
		set(value) {
			field = value
			latencyText?.text = "Latency: $value"
		}

	val bag = LinkedHashMap<String, Int>()

	fun bagUpdated() {
		val gold = bag["gold"] ?: 0
		moneyText.text = "Gold: $gold"
	}

	suspend inline fun <reified T : Any> send(packet: T) = run { ws?.sendPacket(packet, T::class) }
	suspend fun receive(): Any? = ws?.receivePacket()

	lateinit var moneyText: Text
	var latencyText: Text? = null

	override suspend fun Container.sceneInit() {
		val sceneView = this
		init()
		entityContainer.onClick {
			//val pos = it.currentPosLocal.copy() // @TODO: CHECK
			val pos = it.currentPosGlobal.copy() // @TODO: CHECK
			launchImmediately {
				//println("CLICK")
				ws?.sendPacket(ClientRequestMove((pos.x / 32.0).toInt(), (pos.y / 32.0).toInt()))
			}
		}

		//entityContainer.addChild(views.tiledMap(resourcesRoot["tileset1.tsx"].readTiledMap(views)))
		//entityContainer.addChild(views.tiledMap(resourcesRoot["tilemap1.tmx"].readTiledMap(views)))
		entityContainer.addChild(resourcesRoot["library1.tmx"].readTiledMap().createView())
		entityContainer.keepChildrenSortedByY()
		entityContainer
		conversationOverlay
		sceneView.addChild(simpleButton(160, 80, "SAY", resourceManager.font, scope) {
			val text = browser.prompt("What to say?", "")
			ws?.sendPacket(ClientSay(text))
		})
		sceneView.addChild(simpleButton(160, 80, "DEBUG", resourceManager.font, scope) {
			views.debugViews = !views.debugViews
		}.apply { y = 82.0 })
		moneyText = Text("", textSize = 48.0, font = resourceManager.font).apply {
			x = 256.0
			sceneView += this
			mouseEnabled = false
		}
		latencyText = Text("", textSize = 48.0, font = resourceManager.font).apply {
			x = 800.0
			sceneView += this
			mouseEnabled = false
		}
		bagUpdated()
	}
}
