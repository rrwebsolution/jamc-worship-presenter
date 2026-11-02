package com.jamc.worship.remote;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.util.Map;

/** Draws the QR code a phone scans to open the Remote Address. */
public final class RemoteQrCode {

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    /** The white border scanners need around the code, in modules. */
    private static final int QUIET_ZONE = 4;

    private RemoteQrCode() {
    }

    /** One pixel per module, on white whatever the app theme. Scale it up without smoothing to show it. */
    public static BufferedImage create(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, QUIET_ZONE));
            BufferedImage image = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    image.setRGB(x, y, m.get(x, y) ? BLACK : WHITE);
                }
            }
            return image;
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot make a QR code for: " + text, e);
        }
    }
}
