package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Unit fixture only. NEVER start libtorrent/JNI on an owner device. */
class TorrentNativeBatteryGateTest {
    @Test fun nativeTorrentNeedsAtLeastThirtyPercentIndependentlyOfCharging(){
        assertFalse(TorrentSafetyGate.batterySafeForNative(null,100))
        assertFalse(TorrentSafetyGate.batterySafeForNative(-1,100))
        assertFalse(TorrentSafetyGate.batterySafeForNative(29,100))
        assertFalse(TorrentSafetyGate.batterySafeForNative(22,100))
        assertFalse(TorrentSafetyGate.batterySafeForNative(30,0))
        assertTrue(TorrentSafetyGate.batterySafeForNative(30,100))
        assertTrue(TorrentSafetyGate.batterySafeForNative(300,1000))
        assertFalse(TorrentSafetyGate.batterySafeForNative(299,1000))
    }
}
