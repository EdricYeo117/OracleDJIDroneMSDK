package dji.sampleV5.aircraft.remote

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfo {
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
