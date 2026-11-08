package com.mk.iptv

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mk.iptv.cast.Cast
import com.mk.iptv.ui.Actions
import com.mk.iptv.ui.CategoryScreen
import com.mk.iptv.ui.DownloadedSeriesScreen
import com.mk.iptv.ui.HomeScreen
import com.mk.iptv.ui.LocalActions
import com.mk.iptv.ui.MkTheme
import com.mk.iptv.ui.PlaylistEditorScreen
import com.mk.iptv.ui.PlaylistsScreen
import com.mk.iptv.ui.ProfileEditorScreen
import com.mk.iptv.ui.ProfilesScreen
import com.mk.iptv.ui.SearchScreen
import com.mk.iptv.ui.SeriesDetailScreen
import com.mk.iptv.ui.WelcomeScreen
import java.net.URLDecoder

/** AppCompatActivity (a FragmentActivity) so the Cast route chooser dialog can be shown. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Cast.context(this) // warm up the Cast framework so devices are discovered early

        val data = MkApp.graph.store.data.value
        val start = when {
            data.playlists.isEmpty() -> "welcome"
            data.profiles.size > 1 -> "profiles"
            else -> "home"
        }

        setContent {
            MkTheme {
                val nav = rememberNavController()
                val actions = remember(nav) { Actions(nav) }
                CompositionLocalProvider(LocalActions provides actions) {
                    NavHost(
                        nav, startDestination = start,
                        enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
                        popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() },
                    ) {
                        composable("welcome") { WelcomeScreen() }
                        composable("profiles") { ProfilesScreen() }
                        composable("home") { HomeScreen() }
                        composable("search") { SearchScreen() }
                        composable("playlists") { PlaylistsScreen() }
                        composable("series/{id}", listOf(navArgument("id") { type = NavType.IntType })) {
                            SeriesDetailScreen(it.arguments?.getInt("id") ?: 0)
                        }
                        composable("category/{type}/{cat}/{title}") {
                            CategoryScreen(
                                it.arguments?.getString("type") ?: "movie",
                                dec(it.arguments?.getString("cat")),
                                dec(it.arguments?.getString("title")),
                            )
                        }
                        composable("downloads/{key}") { DownloadedSeriesScreen(dec(it.arguments?.getString("key"))) }
                        composable("playlist?id={id}", listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) {
                            PlaylistEditorScreen(it.arguments?.getString("id")?.ifBlank { null })
                        }
                        composable("profile?id={id}", listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) {
                            ProfileEditorScreen(it.arguments?.getString("id")?.ifBlank { null })
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        MkApp.graph.store.flushBlocking()
    }

    private fun dec(s: String?) = URLDecoder.decode(s.orEmpty(), "UTF-8")
}
