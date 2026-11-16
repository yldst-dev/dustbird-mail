@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.dustbird.mail.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dustbird.mail.R
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.accounts.add.AddAccountViewModel
import app.dustbird.mail.ui.accounts.add.AddStep
import app.dustbird.mail.ui.accounts.add.ChooseStep
import app.dustbird.mail.ui.accounts.add.FormStep
import app.dustbird.mail.ui.accounts.add.PrepareStep
import app.dustbird.mail.ui.accounts.add.RolesStep
import app.dustbird.mail.ui.accounts.add.SyncStep
import app.dustbird.mail.ui.accounts.add.VerifyStep
import app.dustbird.mail.ui.accounts.add.rememberMotion
import app.dustbird.mail.ui.accounts.add.stepTitle
import kotlinx.coroutines.flow.map

@Composable
fun AddAccountRoute(onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel<AddAccountViewModel>(
        factory = viewModelFactory { initializer { AddAccountViewModel(container, createSavedStateHandle()) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasAccounts by remember(container) { container.accounts.accounts.map { it.isNotEmpty() } }
        .collectAsStateWithLifecycle(initialValue = false)
    val motion = rememberMotion()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val progress by animateFloatAsState((state.step.ordinal + 1f) / AddStep.entries.size, motion.effects(), label = "progress")
    val progressLabel = stringResource(R.string.add_account_progress)

    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    BackHandler(enabled = state.step != AddStep.CHOOSE) { viewModel.back() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(stepTitle(state.step))) },
                navigationIcon = {
                    if (state.step != AddStep.CHOOSE || hasAccounts) {
                        IconButton(onClick = { if (state.step == AddStep.CHOOSE) onBack() else viewModel.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_account_navigate_back))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
                drawStopIndicator = {},
            )
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (!motion.enabled) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(motion.spatial()) { direction * it / 4 } + fadeIn(motion.effects())) togetherWith
                            (slideOutHorizontally(motion.spatial()) { -direction * it / 4 } + fadeOut(motion.effects()))
                    }
                },
                label = "step",
            ) { step ->
                val providerId = state.providerId
                when {
                    step == AddStep.CHOOSE || providerId == null -> ChooseStep(viewModel::choose)
                    step == AddStep.PREPARE -> PrepareStep(providerId, state.acknowledged, viewModel::acknowledge, viewModel::proceed)
                    step == AddStep.FORM -> FormStep(providerId, viewModel.fields, state.showErrors, motion, viewModel::verify)
                    step == AddStep.VERIFY -> VerifyStep(state, viewModel::save, viewModel::backToForm, viewModel::verify)
                    step == AddStep.SYNC -> SyncStep(state, viewModel::retrySync, viewModel::continueAfterSync, viewModel::finish)
                    else -> RolesStep(state, viewModel::selectRole, viewModel::confirmRoles)
                }
            }
        }
    }
}
