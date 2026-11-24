package io.github.tqm470.figurinhas

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.tqm470.figurinhas.ui.EditorScreen
import io.github.tqm470.figurinhas.ui.HomeScreen
import io.github.tqm470.figurinhas.ui.PackScreen
import io.github.tqm470.figurinhas.ui.theme.FigurinhasTheme

class MainActivity : ComponentActivity() {

    /** Media shared into the app from another app, waiting to be opened in the editor. */
    private val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            FigurinhasTheme {
                val nav = rememberNavController()

                val shared = sharedUri.value
                LaunchedEffect(shared) {
                    if (shared != null) {
                        nav.navigate(Routes.editor(shared, null))
                        sharedUri.value = null
                    }
                }

                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenPack = { nav.navigate(Routes.pack(it)) },
                            onEditMedia = { uri -> nav.navigate(Routes.editor(uri, null)) },
                        )
                    }
                    composable(
                        Routes.PACK,
                        arguments = listOf(navArgument("packId") { type = NavType.StringType }),
                    ) { entry ->
                        val packId = entry.arguments?.getString("packId").orEmpty()
                        PackScreen(
                            packId = packId,
                            onBack = { nav.popBackStack() },
                            onEditMedia = { uri -> nav.navigate(Routes.editor(uri, packId)) },
                        )
                    }
                    composable(
                        Routes.EDITOR,
                        arguments = listOf(
                            navArgument("uri") { type = NavType.StringType },
                            navArgument("packId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
                        val packId = entry.arguments?.getString("packId")
                        EditorScreen(
                            uri = uri,
                            initialPackId = packId,
                            onClose = { nav.popBackStack() },
                            onSaved = { savedPackId ->
                                if (packId == savedPackId) {
                                    nav.popBackStack()
                                } else {
                                    nav.navigate(Routes.pack(savedPackId)) {
                                        popUpTo(Routes.HOME)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) sharedUri.value = uri
    }
}

object Routes {
    const val HOME = "home"
    const val PACK = "pack/{packId}"
    const val EDITOR = "editor?uri={uri}&packId={packId}"

    fun pack(id: String) = "pack/$id"
    fun editor(uri: Uri, packId: String?) =
        "editor?uri=${Uri.encode(uri.toString())}" + (packId?.let { "&packId=$it" } ?: "")
}
