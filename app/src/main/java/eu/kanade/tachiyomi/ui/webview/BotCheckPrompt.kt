package eu.kanade.tachiyomi.ui.webview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.notification.BotChecks

/**
 * KMK (r116): In place of a list's error (or its spinner) while the source waits for a bot check: "Solve the check
 * to continue" with Solve it ([BotChecks.solve], you pass it in WebView). The app never passes it for you.
 */
@Composable
fun BotCheckPrompt(
    waiting: BotChecks.Waiting,
    onSolve: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Solve the check to continue",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        val requests = when (waiting.pending) {
            0 -> ""
            1 -> " · 1 request waiting"
            else -> " · ${waiting.pending} requests waiting"
        }
        Text(
            text = "${waiting.name} is asking for a bot check$requests. Solve it opens the page; it closes by " +
                "itself once you're through, and the list carries on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSolve) { Text("Solve it") }
    }
}

/**
 * KMK (r116): Calls [onSolved] once the source's waiting check ends with your pass (not when it only timed out), so
 * a list that failed while it waited reloads by itself.
 */
@Composable
fun ReloadWhenBotCheckSolved(sourceId: Long?, waiting: BotChecks.Waiting?, onSolved: () -> Unit) {
    val latestOnSolved by rememberUpdatedState(onSolved)
    var openSince by remember { mutableStateOf<Long?>(null) }
    val since = waiting?.since
    LaunchedEffect(sourceId, since) {
        if (since != null) {
            openSince = since
            return@LaunchedEffect
        }
        val was = openSince ?: return@LaunchedEffect
        openSince = null
        val passedAt = sourceId?.let { BotChecks.session(it)?.lastPassedAt } ?: return@LaunchedEffect
        if (passedAt >= was) latestOnSolved()
    }
}
