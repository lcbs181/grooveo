package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Dark modules of the QR code for [text] (true = dark), without quiet zone. */
fun qrModules(text: String): Array<BooleanArray> {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m[x, y] } }
}

/** QR code in black on white (scanners need the contrast, whatever the theme). */
@Composable
fun QrCode(text: String, size: Dp) {
    val modules = remember(text) { qrModules(text) }
    Canvas(Modifier.size(size).background(Color.White).padding(8.dp)) {
        val cell = this.size.width / modules.size
        for (y in modules.indices) for (x in modules[y].indices) if (modules[y][x]) {
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
