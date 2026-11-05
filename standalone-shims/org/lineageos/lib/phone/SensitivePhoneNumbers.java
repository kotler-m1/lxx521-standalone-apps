package org.lineageos.lib.phone;

import android.content.Context;

import org.lineageos.lib.phone.spn.Item;

import java.util.ArrayList;

/**
 * Temporary standalone compatibility shim.
 *
 * This does not load the LineageOS sensitive_pn.xml data.
 * Replace it with a real implementation before production use.
 */
public final class SensitivePhoneNumbers {
    private static final SensitivePhoneNumbers INSTANCE =
            new SensitivePhoneNumbers();

    private SensitivePhoneNumbers() {
    }

    public static SensitivePhoneNumbers getInstance() {
        return INSTANCE;
    }

    public ArrayList<Item> getSensitivePnInfosForMcc(String mcc) {
        return new ArrayList<>();
    }

    public boolean isSensitiveNumber(
            Context context,
            String numberToCheck,
            int subId) {
        return false;
    }
}
