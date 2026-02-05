package com.maciekhetman.cubetimer

data class Cube(
    val id: String,
    val brand: String,
    val model: String,
    val type: Mode,
    val features: List<String> = emptyList(),
    val tension: String = "",
    val centerTravel: String = "",
    val lubes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() {
            val name = listOf(brand, model)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return name.ifBlank { "Unnamed Cube" }
        }
}
