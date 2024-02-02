package data.system

object OrbitalNames {
    fun getSector(name: String): String = name.substring(0, name.indexOf("-"))
    fun getSectorSystem(name: String): String = name.substring(0, name.lastIndexOf("-"))
}