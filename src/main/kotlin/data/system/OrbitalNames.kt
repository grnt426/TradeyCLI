package data.system

object OrbitalNames {
    fun getSector(name: String): String = name.substring(0, 2)
    fun getSectorSystem(name: String): String = name.substring(0, 6)
}