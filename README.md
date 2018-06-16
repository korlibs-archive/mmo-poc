# mmo-poc

MMO Kotlin fullstack development. Server is done using [Ktor](https://ktor.io/) and client is done using [Korge](https://github.com/korlibs/korlibs),
and both communicate by serializing using [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization).

### QuickStart

1. Execute `./gradlew runAll`
2. Open <http://127.0.0.1:8080> with your favourite browser

**Note:** *if it fails, try again since kotlinx.serialization is experimental.*

### Some commands

Compile JS and run the backend serving the client: `./gradlew runAll`
Run the backend only (JVM): `./gradlew :mmo:jvm:runServer`
Run the frontend only (JVM): `./gradlew :mmo:jvm:runClient`
Run the frontend only (JS): `./gradlew :mmo:js:buildAndCopy`

### Technical details

NPCs and server scripts are defined using Kotlin coroutines.
Check an example of NPC here: [Princess.kt](https://github.com/mmo-poc/mmo-poc/blob/618db85851d0a4a0db39ba0b4be72e08fad079b9/mmo/jvm/src/mmo/server/script/Princess.kt)

Serialization 
