package org.deepsymmetry.beatlink;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroupPlayerPerDevice {

    private static final Logger logger = LoggerFactory.getLogger(GroupPlayerPerDevice.class);

    private final Map<InetAddress, DeviceGroup> deviceGroups = new ConcurrentHashMap<>();
    private final Map<InetAddress, Long> lastQueried = new ConcurrentHashMap<>();
    private final Set<InetAddress> activeDbQueryIps = ConcurrentHashMap.newKeySet();

    /** Access all current device groups */
    public Map<InetAddress, DeviceGroup> getDeviceGroups() {
        return deviceGroups;
    }

    /** Check whether a DB port query should run now for a given IP */
    public boolean shouldQueryNow(InetAddress ip, long gracePeriodMs) {
        long now = System.currentTimeMillis();
        Long last = lastQueried.get(ip);
        if (last != null && (now - last) < gracePeriodMs) {
            return false;
        }
        lastQueried.put(ip, now);
        return activeDbQueryIps.add(ip);
    }

    public void markQueryComplete(InetAddress ip) {
        activeDbQueryIps.remove(ip);
    }

    /** actually done slightly different in DeviceGroup class with a synchronized addDevice
     * So this could probably be removed, but was going back and forth where having this made more sense */
    public void safelyAddDevice(InetAddress ip, int playerNumber, String deviceName) {
        DeviceGroup group = deviceGroups.computeIfAbsent(ip, k -> new DeviceGroup());
        synchronized (group) {
            group.addDevice(playerNumber, deviceName, ip);
        }
    }

    public void safelyRemoveDevice(InetAddress ip, int playerNumber, String deviceName) {
        DeviceGroup group = deviceGroups.get(ip);
        if (group != null) {
            synchronized (group) {
                group.removeDevice(playerNumber, deviceName, ip);
                if (group.isEmpty()) {
                    deviceGroups.remove(ip);
                    logger.info("Removed group IP: {}, because it became empty.", ip);
                }
            }
        }
    }

    /** Retrieve or create a device group without modification */
    public DeviceGroup getOrCreateGroup(InetAddress ip) {
        return deviceGroups.computeIfAbsent(ip, k -> new DeviceGroup());
    }
}
