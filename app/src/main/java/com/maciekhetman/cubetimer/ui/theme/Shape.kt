package com.maciekhetman.cubetimer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(24.dp)
val DialogShape = RoundedCornerShape(24.dp)
val ButtonShape = RoundedCornerShape(20.dp)
val ChipShape = RoundedCornerShape(16.dp)

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = ChipShape,
    medium = CardShape,
    large = CardShape,
    extraLarge = DialogShape
)
