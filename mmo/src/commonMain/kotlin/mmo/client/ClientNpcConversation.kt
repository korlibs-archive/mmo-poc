package mmo.client

import com.soywiz.klock.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korim.format.*
import com.soywiz.korio.async.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.*
import mmo.protocol.*

class ClientNpcConversation(
	val resourceManager: ResourceManager,
	val overlay: Container,
	val npcId: Long,
	val conversationId: Long,
	val ws: WebSocketClient,
	val scope: CoroutineScope
) {
	val coroutineContext = resourceManager.coroutineContext

	fun setMood(mood: String) {
	}

	private var bgimageString: String? = null
	private var bgimageBitmap: BmpSlice? = null

	fun setImage(image: String) {
		bgimageString = image
	}

	fun options(text: String, options: List<String>) {
		val imagePlaceholder = Container()

		if (bgimageBitmap == null && bgimageString != null) {
			launchImmediately(coroutineContext) {
				bgimageBitmap = resourceManager.resourcesRoot[bgimageString!!].readBitmapOptimized().slice()
				val image = Image(bgimageBitmap ?: Bitmaps.transparent).apply {
					position(96, -128)
				}
				imagePlaceholder += image
				image.tween(image::alpha[0.0, 1.0], easing = Easing.EASE_IN_OUT_QUAD, time = 0.25.seconds)
			}
		} else {
			imagePlaceholder += Image(bgimageBitmap ?: Bitmaps.transparent).apply {
				position(96, -128)
			}
		}

		overlay.removeChildren()
		overlay += SolidRect(1280.0, 720.0, RGBAf(0, 0, 0, 0.75).rgba)

		overlay += imagePlaceholder

		//val heightSize = 96
		//val textSize = 48.0
		val heightSize = 64
		val textSize = 32.0
		val padding = 2

		val heightWithPadding = heightSize + padding

		overlay.alpha = 0.0
		overlay.enableMouse()
		val mainText = overlay.container {
			this += Text(text, textSize = 36.0, font = resourceManager.font).apply { y = 128.0 }
			alpha = 0.0
			x = -160.0
			scaleX = 0.75
		}
		val buttonsContainer = overlay.container {
		}
		val referenceY = (720 - options.size * heightWithPadding).toDouble()
		val buttons = arrayListOf<Container>()
		for ((index, option) in options.withIndex()) {
			val button = Container().apply {
				this += simpleButton(1280, heightSize, option, resourceManager.font, scope) {
					overlay.disableMouse()
					overlay.removeChildren()
					ws.sendPacket(ClientInteractionResult(npcId, conversationId, index))
				}.apply {
					y = referenceY + (index * heightWithPadding).toDouble()
				}
				x = 250.0 - index * 32.0
				alpha = 0.0 + index * 0.1
			}
			buttonsContainer += button
			buttons += button
		}
		launchImmediately(coroutineContext) {

			overlay.tween(
				overlay::alpha[1.0].easeOutQuad(),
				*buttons.map { it::x[0.0].easeOutQuad() }.toTypedArray(),
				*buttons.map { it::alpha[1.0].easeOutQuad() }.toTypedArray(),
				mainText::alpha[1.0].easeOutQuad(),
				mainText::x[0.0].easeOutQuad(),
				mainText::scaleX[1.0].easeOutQuad(),
				time = 300.milliseconds
			)
		}
	}
}
