package io.github.sweetpotato_digua.mbpu

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 赞助入口是否可见：只在中文界面下提供。
 *
 * 赞赏码是微信的，非中文用户扫不动；赞助页文案目前也只有简体中文，
 * 别的语言会 fallback 出一页简体中文，展示出来只是噪音。
 */
@Composable
internal fun sponsorVisible(): Boolean {
    val code = if (AppThemeState.languageCode == Languages.SYSTEM) {
        Languages.currentSystemCode()
    } else {
        AppThemeState.languageCode
    }
    return code.startsWith("zh")
}

/**
 * 赞助页：纯本地静态页面，不联网、不申请权限、不上报任何信息。
 */
@Composable
fun SponsorScreen(onBack: () -> Unit) {
    val glassBackdrop = rememberGlassBackdrop()
    // L4 修复：原图 1257x1257 完整解码约 6 MiB ARGB，主线程同步解码会卡首帧。
    // 改为 IO 线程异步解码 + inSampleSize=2（628px，240dp 展示下二维码仍清晰），
    // 组合取消时协程随之取消；解码失败保持整块不显示，不去猜图。
    val resources = LocalContext.current.resources
    val qr by produceState<ImageBitmap?>(null, resources) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeResource(resources, R.drawable.sponsor_qr, opts)?.asImageBitmap()
        }
    }
    Scaffold(
        topBar = {
            SettingsTopBar(stringResource(R.string.sponsor), onBack, glassBackdrop)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .asGlassSource(glassBackdrop)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                )
                .verticalScroll(rememberScrollState()),
        ) {
            SmallTitle(
                text = stringResource(R.string.sponsor_title),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 显式分行：与「关于」页更新日志同一处理，兼容资源里是字面量 \n 还是真换行。
                    stringResource(R.string.sponsor_body)
                        .replace("\\n", "\n")
                        .split(Regex("\\r?\\n"))
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                }
            }

            // 赞赏码是整张 1:1 成品图（自带边框、白底码区与底部落款），
            // 整张铺开只做圆角裁切，不再套 Card —— 白底卡片套深色图会显脏，
            // 也没必要再补一层「浅色底缓冲」。
            qr?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.sponsor_qr_desc),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.sponsor_footer),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
