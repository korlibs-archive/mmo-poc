package mmo.shared

object Skins {
    interface Skin{
        val id: Int
        val name: String
    }

    open class Container<T : Skin>(val prefix: String, val skins: List<T>) : Collection<T> by skins {
        val byId = skins.associateBy { it.id }
        val byName = skins.associateBy { it.name }
        operator fun get(id: Int) = byId[id]
        operator fun get(name: String) = byName[name]
    }

    enum class Body(override val id: Int) : Skin {
        none(0),
        princess1(1),
        chubby(2),
        levers(3),
        ;

        companion object : Container<Body>("body/", values().toList())
    }

    enum class Head(override val id: Int) : Skin {
        none(0),
        elf1(1),
        elf2(2),
        ;

        companion object : Container<Head>("head/", values().toList())
    }

    enum class Hair(override val id: Int) : Skin {
        none(0),
        pelo1(1),
        ;

        companion object : Container<Hair>("hair/", values().toList())
    }

    enum class Armor(override val id: Int) : Skin {
        none(0),
        armor1(1),
        ;

        companion object : Container<Armor>("armor/", values().toList())
    }
}
