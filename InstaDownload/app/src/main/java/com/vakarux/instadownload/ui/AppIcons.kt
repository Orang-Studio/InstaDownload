package com.vakarux.instadownload.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object AppIcons {
    val ContentCopy: ImageVector by lazy {
        materialIcon(
            "ContentCopy",
            "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"
        )
    }

    val ContentPaste: ImageVector by lazy {
        materialIcon(
            "ContentPaste",
            "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5C3.9,2 3,2.9 3,4v16" +
                "c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4C21,2.9 20.1,2 19,2zM12,2c0.55,0 1,0.45 1,1" +
                "s-0.45,1 -1,1s-1,-0.45 -1,-1S11.45,2 12,2zM19,20H5V4h2v3h10V4h2V20z"
        )
    }

    val Close: ImageVector by lazy {
        materialIcon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
        )
    }

    val Download: ImageVector by lazy {
        materialIcon("Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z")
    }

    val Image: ImageVector by lazy {
        materialIcon(
            "Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14" +
                "C20.1,21 21,20.1 21,19zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5L8.5,13.5z"
        )
    }

    val Movie: ImageVector by lazy {
        materialIcon(
            "Movie",
            "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4C2.9,4 2.01,4.9 2.01,6L2,18" +
                "c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4H18z"
        )
    }

    val PlayArrow: ImageVector by lazy {
        materialIcon("PlayArrow", "M8,5v14l11,-7z")
    }

    val Fullscreen: ImageVector by lazy {
        materialIcon(
            "Fullscreen",
            "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z"
        )
    }

    val FullscreenExit: ImageVector by lazy {
        materialIcon(
            "FullscreenExit",
            "M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,5h-2v5h5L19,8h-3L16,5z"
        )
    }

    val Pause: ImageVector by lazy {
        materialIcon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    }

    val Settings: ImageVector by lazy {
        materialIcon(
            "Settings",
            "M19.43,12.98c0.04,-0.32 0.07,-0.65 0.07,-0.98s-0.02,-0.66 -0.07,-0.98l2.11,-1.65" +
                "c0.19,-0.15 0.24,-0.42 0.12,-0.64l-2,-3.46c-0.12,-0.22 -0.37,-0.31 -0.6,-0.22l-2.49,1" +
                "c-0.52,-0.4 -1.08,-0.73 -1.69,-0.98L14.5,2.42C14.47,2.18 14.25,2 14,2h-4" +
                "c-0.25,0 -0.46,0.18 -0.5,0.42L9.12,5.07c-0.61,0.25 -1.17,0.59 -1.69,0.98l-2.49,-1" +
                "c-0.23,-0.08 -0.48,0 -0.6,0.22l-2,3.46c-0.13,0.22 -0.07,0.49 0.12,0.64l2.11,1.65" +
                "C4.52,11.34 4.5,11.67 4.5,12s0.02,0.66 0.07,0.98l-2.11,1.65c-0.19,0.15 -0.24,0.42 -0.12,0.64" +
                "l2,3.46c0.12,0.22 0.37,0.31 0.6,0.22l2.49,-1c0.52,0.4 1.08,0.73 1.69,0.98l0.38,2.65" +
                "c0.04,0.24 0.25,0.42 0.5,0.42h4c0.25,0 0.46,-0.18 0.5,-0.42l0.38,-2.65" +
                "c0.61,-0.25 1.17,-0.58 1.69,-0.98l2.49,1c0.23,0.08 0.48,0 0.6,-0.22l2,-3.46" +
                "c0.12,-0.22 0.07,-0.49 -0.12,-0.64L19.43,12.98zM12,15.5A3.5,3.5 0,1 1,12,8a3.5,3.5 0,0 1,0,7.5z"
        )
    }
}

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
