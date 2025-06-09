package org.deepsymmetry.beatlink;

import java.net.InetAddress;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeviceGroup {
    private static final Logger logger = LoggerFactory.getLogger(DeviceGroup.class);

    private final Set<Integer> playerNumbers = new TreeSet<>();
    private final Set<String> deviceNames = new HashSet<>();
    public Integer dbPort = null;

    public synchronized void addDevice(int playerNumber, String name, InetAddress ip) {
        boolean wasNew = playerNumbers.add(playerNumber);
        deviceNames.add(name);

        if (wasNew) {
            logger.info("Added device number: {}, name: {}, to group IP: {}", playerNumber, name, ip);
        } else {
            logger.info("Device number {}, name: {}, already exists in group IP: {}", playerNumber, name, ip);
        }
    }

    public synchronized void removeDevice(int playerNumber, String name, InetAddress ip) {
        if (playerNumbers.remove(playerNumber)) {
            logger.info("Removed device number: {}, name: {}, from group IP: {}", playerNumber, name, ip);
        }
        deviceNames.remove(name); // Remove name too, even if number was missing
    }

    public synchronized boolean isEmpty() {
        return playerNumbers.isEmpty();
    }

    public synchronized String getSummary() {
        return "Players: " + playerNumbers + ", Names: " + deviceNames;
    }

    public synchronized Set<Integer> getPlayerNumbers() {
        return new TreeSet<>(playerNumbers);
    }

    public synchronized Set<String> getDeviceNames() {
        return new HashSet<>(deviceNames);
    }
}