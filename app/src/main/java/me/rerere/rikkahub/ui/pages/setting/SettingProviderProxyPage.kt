package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderProxy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R

/**
 * 代理配置页面（Tab 2）。
 * 类似 LastChat 的 Proxy 配置。
 */
@Composable
fun SettingProviderProxyPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    var internalProvider by remember(provider) { mutableStateOf(provider) }
    val proxy = internalProvider.proxy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_proxy),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.setting_provider_page_proxy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 代理类型选择
        val proxyTypes = listOf(
            ProviderProxy.None::class to stringResource(R.string.setting_provider_page_proxy_none),
            ProviderProxy.Http::class to "HTTP",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            proxyTypes.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, proxyTypes.size),
                    selected = proxy::class == type,
                    onClick = {
                        val newProxy = when (type) {
                            ProviderProxy.None::class -> ProviderProxy.None
                            ProviderProxy.Http::class -> ProviderProxy.Http(
                                address = (proxy as? ProviderProxy.Http)?.address ?: "",
                                port = (proxy as? ProviderProxy.Http)?.port ?: 8080,
                                username = (proxy as? ProviderProxy.Http)?.username ?: "",
                                password = (proxy as? ProviderProxy.Http)?.password ?: "",
                            )
                            else -> proxy
                        }
                        internalProvider = internalProvider.copyProvider(proxy = newProxy)
                    },
                    label = { Text(label) },
                )
            }
        }

        // HTTP 代理配置
        if (proxy is ProviderProxy.Http) {
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = proxy.address,
                onValueChange = { newAddress ->
                    internalProvider = internalProvider.copyProvider(
                        proxy = proxy.copy(address = newAddress)
                    )
                },
                label = { Text(stringResource(R.string.setting_provider_page_proxy_address)) },
                placeholder = { Text("127.0.0.1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = proxy.port.toString(),
                onValueChange = { text ->
                    val port = text.filter { it.isDigit() }.take(5).toIntOrNull()
                        ?.coerceIn(1, 65535) ?: 8080
                    internalProvider = internalProvider.copyProvider(
                        proxy = proxy.copy(port = port)
                    )
                },
                label = { Text(stringResource(R.string.setting_provider_page_proxy_port)) },
                placeholder = { Text("8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            OutlinedTextField(
                value = proxy.username,
                onValueChange = { newUsername ->
                    internalProvider = internalProvider.copyProvider(
                        proxy = proxy.copy(username = newUsername)
                    )
                },
                label = { Text(stringResource(R.string.setting_provider_page_proxy_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = proxy.password,
                onValueChange = { newPassword ->
                    internalProvider = internalProvider.copyProvider(
                        proxy = proxy.copy(password = newPassword)
                    )
                },
                label = { Text(stringResource(R.string.setting_provider_page_proxy_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = null
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        // 保存按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    onEdit(internalProvider)
                },
            ) {
                Text(stringResource(R.string.setting_provider_page_save))
            }
        }
    }
}
