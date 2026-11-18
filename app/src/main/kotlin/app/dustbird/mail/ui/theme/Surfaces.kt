package app.dustbird.mail.ui.theme

import app.dustbird.mail.R
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Pill: Shape = CircleShape
val CapsuleGap: Dp = 6.dp
val CapsulePadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun capsuleShape(): Shape = MaterialTheme.shapes.extraExtraLarge

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun cardShape(): Shape = MaterialTheme.shapes.largeIncreased

@Composable
fun capsuleItemShapes(): ListItemShapes = itemShapes(capsuleShape())

@Composable
fun cardItemShapes(): ListItemShapes = itemShapes(cardShape())

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun itemShapes(shape: Shape) = ListItemShapes(shape, shape, shape, shape, shape, shape)

@Composable
fun Capsule(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) = Group(capsuleShape(), modifier, color, content)

@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) = Group(cardShape(), modifier, color, content)

@Composable
private fun Group(shape: Shape, modifier: Modifier, color: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = color,
        contentColor = contentColorFor(color),
        content = content,
    )
}

@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun groupedTopAppBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

class MenuEntry private constructor(
    @StringRes val label: Int,
    val text: String?,
    @DrawableRes val icon: Int,
    val checked: Boolean,
    val onClick: () -> Unit,
) {
    constructor(@StringRes label: Int, @DrawableRes icon: Int, onClick: () -> Unit) : this(label, null, icon, false, onClick)

    constructor(text: String, @DrawableRes icon: Int, checked: Boolean, onClick: () -> Unit) : this(0, text, icon, checked, onClick)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlatMenu(expanded: Boolean, onDismissRequest: () -> Unit, groups: List<List<MenuEntry>>) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismissRequest) {
        groups.forEachIndexed { index, entries ->
            if (index > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(index, groups.size),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                entries.forEachIndexed { position, entry ->
                    DropdownMenuItem(
                        onClick = {
                            onDismissRequest()
                            entry.onClick()
                        },
                        text = { Text(entry.text ?: stringResource(entry.label)) },
                        shape = MenuDefaults.itemShape(position, entries.size).shape,
                        leadingIcon = { Icon(painterResource(entry.icon), contentDescription = null) },
                        trailingContent = if (entry.checked) {
                            { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.menu_checked)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun FlatSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            shape = capsuleShape(),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(data.visuals.message, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                data.visuals.actionLabel?.let { label ->
                    TextButton(
                        onClick = data::performAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
fun flatFabElevation(): FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)

@Composable
fun pillFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun pillToggleShapes(): ToggleButtonShapes = ToggleButtonShapes(shape = Pill, pressedShape = Pill, checkedShape = Pill)
