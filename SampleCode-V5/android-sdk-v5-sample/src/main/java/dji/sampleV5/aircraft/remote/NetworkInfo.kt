package dji.sampleV5.aircraft.remote

/**
 * Remote module file `NetworkInfo.kt`: contains NetworkInfo implementation details.
 */

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfo {
    // Handles `getLocalIpv4` behavior for the remote control module.
    fun getLocalIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { ip ->
                    ip != "127.0.0.1" && !ip.startsWith("169.254.")
                }
        } catch (_: Throwable) {
            null
        }
    }
}
