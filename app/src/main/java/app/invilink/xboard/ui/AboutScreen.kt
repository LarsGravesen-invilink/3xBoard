package app.invilink.xboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.invilink.xboard.BuildConfig
import app.invilink.xboard.R

@Composable
private fun LicenseScreen(onBack: () -> Unit) {
    val palette = LocalXBoardPalette.current
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BoardIconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }) {
                    BoardIcon(BoardSymbol.Back, palette.foreground)
                }
                Text("Лицензия и права", Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = palette.foreground)
            }
            Column(Modifier.widthIn(max = 808.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Исходный код 3xBoard распространяется на условиях лицензии MIT. При распространении копий или существенных частей кода необходимо сохранять уведомление об авторских правах и текст разрешения. Текст лицензии ниже является определяющим; этот абзац приведён для удобства.",
                    color = palette.foreground, style = MaterialTheme.typography.bodyMedium)
                Text("Мы просим при публикации форков и производных работ указывать авторство и ссылку на оригинальный исходный код: https://github.com/LarsGravesen-invilink/3xBoard. Эта просьба не является дополнительным условием лицензии MIT.",
                    color = palette.foreground, style = MaterialTheme.typography.bodyMedium)
                Text("Компоненты третьих сторон могут распространяться на собственных условиях.",
                    color = palette.secondary, style = MaterialTheme.typography.bodyMedium)
                Text("""MIT License

Copyright (c) 2026 LarsGravesen

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.""", color = palette.foreground, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, onAgreement: () -> Unit, onEmail: () -> Unit) {
    val palette = LocalXBoardPalette.current
    var licenseOpen by remember { mutableStateOf(false) }
    if (licenseOpen) {
        LicenseScreen { licenseOpen = false }
        return
    }
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = palette.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max = 1120.dp).fillMaxWidth()
                    .heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Назад" }
                ) {
                    BoardIcon(BoardSymbol.Back, palette.foreground)
                }
                Text(
                    "О приложении",
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.foreground
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val bodyHeight = maxHeight
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = bodyHeight).padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.xboard_icon),
                        contentDescription = null,
                        modifier = Modifier.size(144.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "3xBoard",
                        style = MaterialTheme.typography.displayLarge,
                        color = palette.foreground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Менеджер управления 3x-UI",
                        modifier = Modifier.widthIn(max = 560.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.secondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(28.dp))
                    Column(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                            .background(
                                palette.soft,
                                RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Invilink | LarsGravesen",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.foreground,
                            textAlign = TextAlign.Center
                        )
                        BoardTextButton(
                            onClick = onEmail,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                        ) {
                            Text("e-mail: igor@telyuk.ru", textAlign = TextAlign.Center)
                        }
                        Text(
                            "Разработка и поддержка: Игорь Николаевич Т.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.secondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            BoardTextButton(
                onClick = onAgreement,
                modifier = Modifier.widthIn(max = 608.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Text("Соглашение об использовании", textAlign = TextAlign.Center)
            }
            BoardTextButton(
                onClick = { licenseOpen = true },
                modifier = Modifier.widthIn(max = 608.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.foreground),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
            ) { Text("Лицензия и права", textAlign = TextAlign.Center) }
        }
    }
}
