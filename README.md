# mmo-poc

[![Build Status](https://travis-ci.org/mmo-poc/mmo-poc.svg?branch=master)](https://travis-ci.org/mmo-poc/mmo-poc)

MMO Kotlin fullstack development. Server is done using [Ktor](https://ktor.io/) and client is done using [Korge](https://github.com/korlibs/korlibs),
and both communicate by serializing using [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization),
use kotlin scripting for some NPCs, and optionally Redis for storage if available or `REDIST_HOST` env var set.

**Blogs:** [Artist blog](https://tamy.es/tag/mmo/), [Programmer Blog](https://soywiz.com/tag/mmo/)

![](/docs/screenshot.png)

### QuickStart

1. Execute `./gradlew runServer`
2. Open <http://127.0.0.1:8080> with your favourite browser supporting WebGL

### Some commands

* Run the backend (JVM) serving the client (JS): `./gradlew :runServer`

* Generate FatJAR: `./gradlew jvmFatJar`

### Technical details

NPCs and server scripts are defined using Kotlin coroutines.
Check an example of NPC here: [Princess.kt](https://github.com/mmo-poc/mmo-poc/blob/master/mmo/jvm/src/mmo/server/script/Princess.kt)

Frontend is done using Korge and can run in JS or JVM.

Connection happens using websockets, and messages/packets are converted to JSON and back using kotlinx.serialization. 

### Objectives

* Use [Ktor](https://ktor.io/) in a production environment
* Play with Kotlin multiplatform projects before it is released
* Experiment with [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
* Evolve and use in production [Korge game engine](https://github.com/korlibs/korlibs)
* Experimenting with PWA for games that work everywhere
* A graphic playground for experimentation
