package mmo.shared

object Skins {
    interface Skin{
        val id: Int
        val name: String
        val fileName: String
    }

    open class Container<T : Skin>(val prefix: String, val skins: List<T>) : Collection<T> by skins {
        val byId = skins.associateBy { it.id }
        val byName = skins.associateBy { it.fileName } + skins.associateBy { it.name }
        operator fun get(id: Int) = byId[id]
        operator fun get(name: String) = byName[name]
    }

    enum class Body(override val id: Int, fileName: String? = null) : Skin {
        none(0),
        princess1(1),
        chubby(2),
        levers(3),
        chubbyGirl(4, "chubby-girl"),
        girl1(5, "girl1"),
        ;

        override val fileName = fileName ?: name

        companion object : Container<Body>("body/", values().toList())
    }

    enum class Head(override val id: Int, rname: String? = null) : Skin {
        none(0),
        elf1(1),
        elf2(2),
        girl(3, "face-girl-princess"),
        princess(4, "face-girl-princess"),
        eye_whitch(5, "eye-whitch"),
        littlecrown(6, "littlecrown"),
        whitch_hat(7, "whitch_hat"),
        ;

        override val fileName = rname ?: name

        companion object : Container<Head>("head/", values().toList())
    }

    enum class Hair(override val id: Int, rname: String? = null) : Skin {
        none(0),
        pelo1(1, "hair-boy1"),
        girl1(2, "hair-girl1"),
        princess(3, "hair-princess"),
        teach_whitch(4, "teach_whitch")
        ;

        override val fileName = rname ?: name

        companion object : Container<Hair>("hair/", values().toList())
    }

    enum class Armor(override val id: Int, rname: String? = null) : Skin {
        none(0),
        armor1(1),
        princess_dress1(2, "princess-dress"),
        princess_dress2(3, "princess-dress2"),
        princess_dress3(4, "dress-princess"),
        dress_teach_witch(5, "dress_teach_witch")
        ;

        override val fileName = rname ?: name

        companion object : Container<Armor>("armor/", values().toList())
    }
}
