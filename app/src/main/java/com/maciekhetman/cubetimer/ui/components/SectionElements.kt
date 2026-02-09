package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 8.dp
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(top = topPadding)
    )
}

@Composable
fun SectionDivider(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 6.dp
) {
    HorizontalDivider(modifier = modifier.padding(vertical = verticalPadding))
}
