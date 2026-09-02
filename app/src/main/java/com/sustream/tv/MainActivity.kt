package com.sustream.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.widget.Toast
import com.sustream.tv.core.di.AppContainer
import com.sustream.tv.core.di.LocalAppContainer
import com.sustream.tv.designsystem.theme.SuStreamTheme
import com.sustream.tv.presentation.navigation.Routes
import com.sustream.tv.presentation.navigation.SuStreamNavGraph

/**
 * The single activity.
 *
 * Everything above it is Compose. Its jobs are to provide the object graph to the composition, host
 * the navigation graph, and own the top-level BACK policy — which on a television is a real decision
 * rather than a default, because there is no on-screen "up" affordance and BACK is the only way out
 * of anything.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as SuStreamApplication).container

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SuStreamTheme {
                    SuStreamApp(container = container, onExitApp = { finish() })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the player's surface and buffers. The container itself outlives the activity, and
        // its lazy dependencies are rebuilt on demand if the activity returns.
        if (isFinishing) {
            (application as SuStreamApplication).container.playerManager.release()
        }
    }
}

@Composable
private fun SuStreamApp(
    container: AppContainer,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SuStreamTheme.colours.background),
    ) {
        SuStreamNavGraph(navController = navController)
        TopLevelBackPolicy(navController = navController, onExitApp = onExitApp)
    }
}

/**
 * BACK at the top of the stack.
 *
 * The rule the brief asks for — back must be predictable and must not exit unexpectedly — comes down
 * to two behaviours here:
 *
 *  * from any section that is not Home, BACK returns to **Home** rather than to whichever section was
 *    visited before it;
 *  * from Home, BACK requires **two presses** within a short window to leave the app. A single press
 *    closing a media app is jarring on a TV, where BACK is used constantly and is easy to hit twice
 *    by accident when a screen is still loading.
 *
 * Handlers deeper in the tree — the player's layered BACK, a dialog's dismiss — take precedence
 * because Compose dispatches to the innermost enabled handler first, so this only ever runs when
 * nothing closer to the user wanted the event.
 */
@Composable
private fun TopLevelBackPolicy(
    navController: NavHostController,
    onExitApp: () -> Unit,
) {
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    var exitArmed by remember { mutableStateOf(false) }

    // Disarm after the window passes, so a press now and another a minute later does not exit.
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(EXIT_CONFIRM_WINDOW_MILLIS)
            exitArmed = false
        }
    }

    // Enabled only on the rail sections. On Details and the player, their own handlers own BACK.
    BackHandler(enabled = currentRoute in Routes.SECTIONS) {
        if (currentRoute != Routes.HOME) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
            return@BackHandler
        }

        if (exitArmed) {
            onExitApp()
        } else {
            exitArmed = true
            Toast.makeText(context, R.string.exit_confirm, Toast.LENGTH_SHORT).show()
        }
    }
}

private const val EXIT_CONFIRM_WINDOW_MILLIS = 2_500L
