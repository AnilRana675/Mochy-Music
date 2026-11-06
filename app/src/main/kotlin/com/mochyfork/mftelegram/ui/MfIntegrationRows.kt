package com.mochyfork.mftelegram.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.screens.settings.integrations.IntegrationRow

@Composable
fun MfIntegrationRows(navController: NavController) {
    IntegrationRow(
        name = stringResource(R.string.mf_channel_library),
        description = stringResource(R.string.mf_channel_library_desc),
        icon = R.drawable.mf_ic_channel_library,
        onClick = { navController.navigate(MfChannelLibraryRoute) },
    )
}
