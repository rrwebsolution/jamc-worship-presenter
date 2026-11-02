package com.jamc.worship.remote;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Choosing the PC's address, and the QR code made from it. */
class RemoteAddressTest {

    private static LocalNetwork.Candidate real(String ip, String name) {
        return new LocalNetwork.Candidate(ip, name, false);
    }

    @Test
    void theRoutedRealAdapterComesFirstAndVirtualOnesLast() {
        List<LocalNetwork.Candidate> found = List.of(
                new LocalNetwork.Candidate("192.168.56.1", "VirtualBox Host-Only", true),
                real("10.0.0.5", "Ethernet"),
                real("192.168.1.15", "Wi-Fi"));
        assertEquals(List.of("192.168.1.15", "10.0.0.5", "192.168.56.1"),
                LocalNetwork.rank(found, "192.168.1.15").stream().map(LocalNetwork.Address::ip).toList());
    }

    @Test
    void aVpnRouteDoesNotBeatTheRealNetwork() {
        List<LocalNetwork.Candidate> found = List.of(new LocalNetwork.Candidate("10.8.0.2", "WireGuard Tunnel", true),
                real("192.168.1.15", "Wi-Fi"));
        assertEquals("192.168.1.15", LocalNetwork.rank(found, "10.8.0.2").get(0).ip());
    }

    @Test
    void realAdaptersAreKeptInOrderWithoutARoute() {
        List<LocalNetwork.Candidate> found = List.of(real("192.168.1.15", "Wi-Fi"), real("10.0.0.5", "Ethernet"));
        assertEquals("192.168.1.15", LocalNetwork.rank(found, null).get(0).ip());
    }

    @Test
    void virtualAdaptersAreRecognised() {
        assertTrue(LocalNetwork.looksVirtual("vEthernet (WSL (Hyper-V firewall))"));
        assertTrue(LocalNetwork.looksVirtual("VMware Virtual Ethernet Adapter for VMnet8"));
        assertFalse(LocalNetwork.looksVirtual("Intel(R) Wi-Fi 6 AX201 160MHz"));
        assertFalse(LocalNetwork.looksVirtual("Realtek PCIe GbE Family Controller"));
    }

    @Test
    void detectingOnThisPcGivesOnlyPrivateIpv4Addresses() {
        for (LocalNetwork.Address a : LocalNetwork.detect()) {
            assertTrue(a.ip().matches("^(10|172|192)\\..*"), a.toString());
        }
    }

    @Test
    void theQrCodeScansBackToTheAddress() throws Exception {
        String url = "http://192.168.1.15:8080";
        BufferedImage qr = RemoteQrCode.create(url);
        int scale = 6;
        int size = qr.getWidth() * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = qr.getRGB(x / scale, y / scale);
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, pixels)));
        assertEquals(url, new QRCodeReader().decode(bitmap).getText());
    }

    @Test
    void theQrCodeKeepsItsWhiteQuietZone() {
        BufferedImage qr = RemoteQrCode.create("http://10.0.0.5:8080");
        for (int i = 0; i < qr.getWidth(); i++) {
            assertEquals(0xFFFFFFFF, qr.getRGB(i, 0));
            assertEquals(0xFFFFFFFF, qr.getRGB(0, i));
        }
    }
}
