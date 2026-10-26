package app.whittle.design.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Marks what the user and the agent wrote, did and made, which stays out of session recordings while the user says so. */
val LocalUserContent = staticCompositionLocalOf<Modifier> { Modifier }

/** Marks what never goes into a session recording, whatever the user chose: keys and codes. */
val LocalSecretContent = staticCompositionLocalOf<Modifier> { Modifier }

/** This, kept out of session recordings where the device makes them. */
@Composable
fun Modifier.userContent(): Modifier = then(LocalUserContent.current)

/** This, never in a session recording. */
@Composable
fun Modifier.secretContent(): Modifier = then(LocalSecretContent.current)
