import com.rezz.rlnova.DeviceInfo;

/**
 * Test klasifikasi tier DeviceInfo: compile manual, jalankan dengan java
 * (tidak butuh Android framework, hanya logika angka).
 */
public class TestTier {

    static int passed = 0, failed = 0;

    static void check(String label, long ramBytes, DeviceInfo.Tier expected) {
        DeviceInfo.Tier got = DeviceInfo.classify(ramBytes);
        if (got == expected) { failed = 0; System.out.println("PASS " + label + " -> " + got); }
        else { System.out.println("FAIL " + label + " -> " + got + " (harusnya " + expected + ")"); }
    }

    public static void main(String[] args) {
        double G = 1024.0 * 1024.0 * 1024.0;
        // LOW: <= 4 GB (4 GB RAM member = ~3.6 GB terdeteksi)
        check("3 GB RAM", (long) (2.8 * G), DeviceInfo.Tier.LOW);
        check("4 GB RAM (3.6 terdeteksi)", (long) (3.6 * G), DeviceInfo.Tier.LOW);
        check("tepat 4 GB", (long) (4.0 * G), DeviceInfo.Tier.LOW);
        // MID: 6-8 GB
        check("6 GB RAM (5.7 terdeteksi)", (long) (5.7 * G), DeviceInfo.Tier.MID);
        check("8 GB RAM (7.4 terdeteksi)", (long) (7.4 * G), DeviceInfo.Tier.MID);
        check("10 GB RAM", (long) (9.8 * G), DeviceInfo.Tier.HIGH);
        // HIGH: >= 12 GB
        check("12 GB RAM (11.3 terdeteksi)", (long) (11.3 * G), DeviceInfo.Tier.HIGH);
        check("16 GB RAM", (long) (15.5 * G), DeviceInfo.Tier.HIGH);
        // edge
        check("1 GB RAM", (long) (0.9 * G), DeviceInfo.Tier.LOW);
        check("12 GB pas (12.0)", (long) (12.0 * G), DeviceInfo.Tier.HIGH);
        check("di antara 4 dan 6 (4.5 GB)", (long) (4.5 * G), DeviceInfo.Tier.LOW);

        // formatBytes
        String f1 = DeviceInfo.formatBytes((long) (3.6 * G));  // "3,6 GB"
        String f2 = DeviceInfo.formatBytes(807694464L);         // "0,8 GB"
        String f3 = DeviceInfo.formatBytes(396705472L);         // "0,4 GB"
        System.out.println("formatBytes: " + f1 + " | " + f2 + " | " + f3);
    }
}
