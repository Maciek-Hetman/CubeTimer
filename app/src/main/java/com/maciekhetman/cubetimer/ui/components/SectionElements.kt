package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 8.dp,
    style: TextStyle = MaterialTheme.typography.titleMedium
) {
    Text(
        text = title,
        style = style,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = topPadding, bottom = 8.dp)
    )
}

@Composable
fun SectionDivider(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 6.dp
) {
    Spacer(modifier = modifier.height(verticalPadding))
}
