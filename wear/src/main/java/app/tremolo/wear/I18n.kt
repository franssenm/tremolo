package app.tremolo.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.LocalTextStyle
import app.tremolo.shared.i18n.tr

/** The watch's screens use this instead of the library's Text, which gets them translated. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.wear.compose.material3.Text(text = tr(text), modifier = modifier, textAlign = textAlign, style = style)
}
