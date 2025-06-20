package org.deepsymmetry.beatlink.dbserver;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException; // Kevin - Added for requestPlayerDBServerPort & requestDeviceDBServerPortGrouped
import java.net.InetAddress; // Kevin - Added for requestDeviceDBServerPortGrouped & DeviceAnnouncementListener so we can use the IP
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set; // Kevin - Added for requestDeviceDBServerPortGrouped
import java.util.TreeSet; // Kevin - Added for requestDeviceDBServerPortGrouped
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors; // Kevin - Added for DeviceAnnouncementListener to allow wait to aggregate Players into a device Object and prevent Race Condition
import java.util.concurrent.ScheduledExecutorService; // Kevin - Added for DeviceAnnouncementListener to allow wait to aggregate Players into a device Object and prevent Race Condition
import java.util.concurrent.TimeUnit; // Kevin - Added for DeviceAnnouncementListener helping to define the Time for the wait
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manges connections to dbserver ports on the players, offering sessions that can be used to perform transactions,
 * and allowing the connections to close when there are no active sessions.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class ConnectionManager extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /**
     * An interface for all the kinds of activities that need a connection to the dbserver, so we can keep track
     * of how many sessions are in effect, clean up after them, and know when the client is idle and can be closed.
     *
     * @param <T> the type returned by the activity
     */
    @API(status = API.Status.STABLE)
    public interface ClientTask<T> {
        T useClient(Client client) throws Exception;
    }

    /**
     * Keeps track of the clients that are currently active, indexed by player number
     */
    private final Map<Integer,Client> openClients = new ConcurrentHashMap<>();

    /**
     * Keeps track of how many tasks are currently using each client.
     */
    private final Map<Client,Integer> useCounts = new ConcurrentHashMap<>();

    /**
     * Keeps track of the last time each client was used, so we can time out our connection.
     */
    private final Map<Client,Long> timestamps = new ConcurrentHashMap<>();

    /**
     * How many seconds do we allow an idle connection to stay open?
     */
    private final AtomicInteger idleLimit = new AtomicInteger(1);

    /**
     * Determine how long an idle connection will be kept open for reuse. Once this time has elapsed, the connection
     * will be closed. Setting this to zero will close connections immediately after use, which might be somewhat
     * inefficient when multiple queries need to happen in a row, since each will require the establishment of a
     * new connection. The default value is 1.
     *
     * @param seconds how many seconds a connection will be kept open while not being used
     *
     * @throws IllegalArgumentException if a negative value is supplied
     */
    @API(status = API.Status.STABLE)
    public void setIdleLimit(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds cannot be negative");
        }
        idleLimit.set(seconds);
    }

    /**
     * Check how long an idle connection will be kept open for reuse. Once this time has elapsed, the connection
     * will be closed. Setting this to zero will close connections immediately after use, which might be somewhat
     * inefficient when multiple queries need to happen in a row, since each will require the establishment of a
     * new connection. The default value is 1.
     *
     * @return how many seconds a connection will be kept open while not being used
     */
    @API(status = API.Status.STABLE)
    public int getIdleLimit() {
        return idleLimit.get();
    }

    /**
     * Finds or opens a client to talk to the dbserver on the specified player, incrementing its use count.
     *
     * @param targetPlayer the player number whose database needs to be interacted with
     * @param description a short description of the task being performed for error reporting if it fails,
     *                    should be a verb phrase like "requesting track metadata"
     *
     * @return the communication client for talking to that player, or {@code null} if the player could not be found
     *
     * @throws IllegalStateException if we can't find the target player or there is no suitable player number for us
     *                               to pretend to be
     * @throws IOException if there is a problem communicating
     */
    private synchronized Client allocateClient(int targetPlayer, String description) throws IOException {
        Client result = openClients.get(targetPlayer);
        if (result == null) {
            // We need to open a new connection.
            final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getInstance().getLatestAnnouncementFrom(targetPlayer);
            if (deviceAnnouncement == null) {
                throw new IllegalStateException("Player " + targetPlayer + " could not be found " + description);
            }
            final int dbServerPort = getPlayerDBServerPort(targetPlayer);
            if (dbServerPort < 0) {
                throw new IllegalStateException("Player " + targetPlayer + " does not have a db server " + description);
            }

            final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(targetPlayer);

            Socket socket = null;
            try {
                InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
                socket = new Socket();
                socket.connect(address, socketTimeout.get());
                socket.setSoTimeout(socketTimeout.get());
                result = new Client(socket, targetPlayer, posingAsPlayerNumber);
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException e2) {
                    logger.error("Problem closing socket for failed client creation attempt {}", description);
                }
                throw e;
            }
            openClients.put(targetPlayer, result);
            useCounts.put(result, 0);
        }
        useCounts.put(result, useCounts.get(result) + 1);
        return result;
    }

    /**
     * When it is time to actually close a client, do so, and clean up the related data structures.
     *
     * @param client the client which has been idle for long enough to be closed
     */
    private void closeClient(Client client) {
        logger.debug("Closing client {}", client);
        client.close();
        openClients.remove(client.targetPlayer);
        useCounts.remove(client);
        timestamps.remove(client);
    }

    /**
     * Decrements the client's use count, and makes it eligible for closing if it is no longer in use.
     *
     * @param client the dbserver connection client which is no longer being used for a task
     */
    private synchronized void freeClient(Client client) {
        int current = useCounts.get(client);
        if (current > 0) {
            timestamps.put(client, System.currentTimeMillis());  // Mark that it was used until now.
            useCounts.put(client, current - 1);
            if ((current == 1) && (idleLimit.get() == 0)) {
                closeClient(client);  // This was the last use, and we are supposed to immediately close idle clients.
            }
        } else {
            logger.error("Ignoring attempt to free a client that is not allocated: {}", client);
        }
    }

    /**
     * Obtain a dbserver client session that can be used to perform some task, call that task with the client,
     * then release the client.
     *
     * @param targetPlayer the player number whose dbserver we wish to communicate with
     * @param task the activity that will be performed with exclusive access to a dbserver connection
     * @param description a short description of the task being performed for error reporting if it fails,
     *                    should be a verb phrase like "requesting track metadata"
     * @param <T> the type that will be returned by the task to be performed
     *
     * @return the value returned by the completed task
     *
     * @throws IOException if there is a problem communicating
     * @throws Exception from the underlying {@code task}, if any
     */
    @API(status = API.Status.STABLE)
    public <T> T invokeWithClientSession(int targetPlayer, ClientTask<T> task, String description)
            throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("ConnectionManager is not running, aborting " + description);
        }

        final Client client = allocateClient(targetPlayer, description);
        try {
            return task.useClient(client);
        } finally {
            freeClient(client);
        }
    }

    /**
     * Keeps track of the database server ports of all the players we have seen on the network.
     */
    private final Map<Integer, Integer> dbServerPorts = new ConcurrentHashMap<>();

    /**
     * Look up the database server port reported by a given player. You should not use this port directly; instead
     * ask this class for a session to use while you communicate with the database.
     *
     * @param player the player number of interest
     *
     * @return the port number on which its database server is running, or -1 if unknown
     *
     * @throws IllegalStateException if not running
     */
    @API(status = API.Status.STABLE)
    public int getPlayerDBServerPort(int player) {
        ensureRunning();
        Integer result = dbServerPorts.get(player);
        if (result == null) {
            return -1;
        }
        return result;
    }

    /**
     * Kevin - Define the GroupPlayerPerDevice Class as groupManager so we can use it inside DeviceAnnouncementListener
     */
    private final GroupPlayerPerDevice deviceGroupManager = new GroupPlayerPerDevice();

    /**
     * Kevin - Adding ScheduleExecutorService so we can allow DeviceAnnouncementListener to wait a little bit and see if we see multiple players on an IP before we go and request the DB Port
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            if (VirtualCdj.getInstance().inOpusQuadCompatibilityMode()) {
                logger.debug("Nothing to do when new devices found in Opus Quad compatibility mode.");
                return;
            }
            if (announcement.getDeviceNumber() == 25 && announcement.getDeviceName().equals("NXS-GW")) {
                logger.debug("Ignoring departure of Kuvo gateway, which fight each other and come and go constantly, especially in CDJ-3000s.");
                return;
            }
            logger.info("Processing device found, number: {}, name: {}, IP: {}", announcement.getDeviceNumber(), announcement.getDeviceName(), announcement.getAddress());
            //new Thread(() -> requestPlayerDBServerPort(announcement)).start(); // Kevin - Replacing with code to manage grouping

            // Kevin - Derrive the IP, devicenumber and devicename from the announcement request
            InetAddress ip = announcement.getAddress();
            int deviceNumber = announcement.getDeviceNumber();
            String deviceName = announcement.getDeviceName();

            // Kevin - Group devices by IP
            deviceGroupManager.getOrCreateGroup(ip).addDevice(deviceNumber, deviceName, ip);

            // Kevin - Set Timeout, how long will we give other Players to announce themselves so we can actually group them, if we would do it all directly without waiting we could run into issues as we would get multiple devices trying to group at the same time
            long gracePeriod = 2000;
            if (deviceGroupManager.shouldQueryNow(ip, gracePeriod)) {
                //new Thread(() -> requestDeviceDBServerPortGrouped(ip)).start();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.schedule(() -> {
                    requestDeviceDBServerPortGrouped(ip);
                    scheduler.shutdown(); // Shut it down after the task finishes
                }, gracePeriod, TimeUnit.MILLISECONDS);

            }
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            InetAddress ip = announcement.getAddress();
            int playerNumber = announcement.getDeviceNumber();
            String deviceName = announcement.getDeviceName();

            if (playerNumber == 25 && "NXS-GW".equals(deviceName)) {
                logger.debug("Ignoring arrival of Kuvo gateway, which fight each other and come and go constantly, especially in CDJ-3000s.");
                return;
            }

            logger.info("Device lost: number {}, name {}, IP {}", playerNumber, deviceName, ip);

            // Remove from db server port map
            dbServerPorts.remove(playerNumber);

            // Remove from the device group
            deviceGroupManager.safelyRemoveDevice(ip, playerNumber, deviceName);
        }
    };


    /**
     * Record the database server port reported by a player.
     *
     * @param player the player number whose server port has been determined.
     * @param port the port number on which the player's database server is running.
     */
    private void setPlayerDBServerPort(int player, int port) {
        dbServerPorts.put(player, port);
    }

    /**
     * The port on which we can request information about a player, including the port on which its database server
     * is running.
     */
    private static final int DB_SERVER_QUERY_PORT = 12523;

    private static final byte[] DB_SERVER_QUERY_PACKET = {
            0x00, 0x00, 0x00, 0x0f,
            0x52, 0x65, 0x6d, 0x6f, 0x74, 0x65, 0x44, 0x42, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72,  // RemoteDBServer
            0x00
    };

    /**
     * Query a player to determine the port on which its database server is running.
     *
     * @param announcement the device announcement with which we detected a new player on the network.
     */
    private void requestPlayerDBServerPort(DeviceAnnouncement announcement) {
        // logger.info("Trying to determine database server port for device number " + announcement.getNumber());
        for (int tries = 0; tries < 4; ++tries) {

            if (tries > 0) {
                try {
                    Thread.sleep(1000 * tries);  // Give the player more time to be ready, it may be booting.
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while trying to retry dbserver port query?");
                }
            }

            Socket socket = null;
            try {
                InetSocketAddress address = new InetSocketAddress(announcement.getAddress(), DB_SERVER_QUERY_PORT);

                logger.info("Attempting RemoteDBServer request to player {} at IP {}", announcement.getDeviceNumber(), announcement.getAddress()); // Kevin - Adding Logging so we know which device is getting queried

                socket = new Socket();
                socket.connect(address, socketTimeout.get());
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();
                socket.setSoTimeout(socketTimeout.get());
                os.write(DB_SERVER_QUERY_PACKET);
                byte[] response = readResponseWithExpectedSize(is);
                if (response.length == 2) {
                    final int portReturned = (int)Util.bytesToNumber(response, 0, 2);
                    if (portReturned == 65535) {
                        logger.info("Player {} at IP {} reported dbserver port of {}, not yet ready?", announcement.getDeviceNumber(), announcement.getAddress(), portReturned); // Kevin - Added IP to this for better logging
                    } else {
                        setPlayerDBServerPort(announcement.getDeviceNumber(), portReturned);
                        return;  // Success!
                    }
                }
            } catch (java.net.ConnectException ce) {
                logger.info("Player {} at IP {} doesn't answer rekordbox port queries, connection refused, not yet ready?", announcement.getDeviceNumber(), announcement.getAddress()); // Kevin - Added IP to this for better logging
            } catch (Throwable t) {
                logger.warn("Problem requesting database server port number from player {} at IP {}", announcement.getDeviceNumber(), announcement.getAddress(), t); // Kevin - Changed this line to get a better idea on which device we had a failure
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        logger.warn("Problem closing database server port request socket", e);
                    }
                }
            }
        }

        logger.info("Player {} never responded with a valid rekordbox dbserver port. Won't attempt to request metadata.",
                announcement.getDeviceNumber());
    }

    /**
     * Kevin - Query a Device (not a specific player) to determine the port on which its database server is running.
     *
     * @param ip of the aggregated (if applicable) device object which we detected a new player on the network.
     */
    private void requestDeviceDBServerPortGrouped(InetAddress ip) {
        DeviceGroup group = deviceGroupManager.getOrCreateGroup(ip);

        if (group == null || group.isEmpty()) {
            logger.warn("Skipping DB port request for IP {}: No device group or players found.", ip);
            deviceGroupManager.markQueryComplete(ip);
            return;
        }

        Set<Integer> players;
        synchronized (group) {
            players = new TreeSet<>(group.getPlayerNumbers()); // defensive copy
        }

        logger.debug("Requesting DB server port for IP {} with players: {}", ip, players);

        try {
            for (int attempt = 0; attempt < 4; ++attempt) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(1000 * attempt); // Back-off retry
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted while retrying DB port request for IP {}", ip);
                    }
                }

                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, DB_SERVER_QUERY_PORT), socketTimeout.get());
                    socket.setSoTimeout(socketTimeout.get());

                    OutputStream os = socket.getOutputStream();
                    InputStream is = socket.getInputStream();

                    os.write(DB_SERVER_QUERY_PACKET);
                    byte[] response = readResponseWithExpectedSize(is);

                    if (response.length == 2) {
                        int port = (int) Util.bytesToNumber(response, 0, 2);
                        if (port != 65535) {
                            synchronized (group) {
                                group.dbPort = port;
                                for (int playerNumber : group.getPlayerNumbers()) {
                                    setPlayerDBServerPort(playerNumber, port);
                                }
                            }

                            logger.info("Discovered DB server port {} at {} for player(s) {}", port, ip, players);
                            return;
                        } else {
                            logger.info("DB server not ready at {}: port 65535", ip);
                        }
                    }
                } catch (ConnectException ce) {
                    logger.info("Connection refused when querying DB port at {} (retry {})", ip, attempt + 1);
                } catch (IOException e) {
                    logger.warn("IO error while querying DB server port at {}: {}", ip, e.getMessage());
                }
            }

            logger.warn("Failed to get a valid DB server port from IP {} after 4 attempts", ip);
        } finally {
            deviceGroupManager.markQueryComplete(ip);
        }
    }

    /**
     * The default value we will use for timeouts on opening and reading from sockets.
     */
    @API(status = API.Status.STABLE)
    public static final int DEFAULT_SOCKET_TIMEOUT = 10000;

    /**
     * The number of milliseconds after which an attempt to open or read from a socket will fail.
     */
    private final AtomicInteger socketTimeout = new AtomicInteger(DEFAULT_SOCKET_TIMEOUT);

    /**
     * Set how long we will wait for a socket to connect or for a read operation to complete.
     * Adjust this if your players or network require it.
     *
     * @param timeout after how many milliseconds will an attempt to open or read from a socket fail
     */
    @API(status = API.Status.STABLE)
    public void setSocketTimeout(int timeout) {
        socketTimeout.set(timeout);
    }

    /**
     * Check how long we will wait for a socket to connect or for a read operation to complete.
     * Adjust this if your players or network require it.
     *
     * @return the number of milliseconds after which an attempt to open or read from a socket will fail
     */
    @API(status = API.Status.STABLE)
    public int getSocketTimeout() {
        return socketTimeout.get();
    }

    /**
     * Receive some bytes from the player we are requesting metadata from.
     *
     * @param is the input stream associated with the player metadata socket.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response
     */
    private byte[] receiveBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int len = (is.read(buffer));
        if (len < 1) {
            throw new IOException("receiveBytes read " + len + " bytes.");
        }
        return Arrays.copyOf(buffer, len);
    }

    /**
     * Receive an expected number of bytes from the player, logging a warning if we get a different number of them.
     *
     * @param is the input stream associated with the player metadata socket.
     * @return the bytes read.
     * @throws IOException if there is a problem reading the response.
     */
    private byte[] readResponseWithExpectedSize(InputStream is) throws IOException {
        byte[] result = receiveBytes(is);
        if (result.length != 2) {
            logger.warn("Expected " + 2 + " bytes while reading database server port query packet response, received {}", result.length);
        }
        return result;
    }

    /**
     * Finds a valid  player number that is currently visible but which is different from the one specified, so it can
     * be used as the source player for a query being sent to the specified one. If the virtual CDJ is running on an
     * acceptable player number (which must be 1-4 to request metadata from an actual CDJ, but can be anything if we
     * are talking to rekordbox), uses that, since it will always be safe. Otherwise, tries to borrow the player number
     * of another actual CDJ on the network, but we can't do that if the player we want to impersonate has mounted
     * a track from the player that we want to talk to.
     *
     * @param targetPlayer the player to which a metadata query is being sent
     *
     * @return some other currently active player number, ideally not a real player, but sometimes we have to
     *
     * @throws IllegalStateException if there is no other player number available to use
     */
    private int chooseAskingPlayerNumber(int targetPlayer) {
        final int fakeDevice = VirtualCdj.getInstance().getDeviceNumber();
        if ((targetPlayer > 15) || (fakeDevice >= 1 && fakeDevice <= 4)) {
            return fakeDevice;
        }

        for (DeviceAnnouncement candidate : DeviceFinder.getInstance().getCurrentDevices()) {
            final int realDevice = candidate.getDeviceNumber();
            if (realDevice != targetPlayer && realDevice >= 1 && realDevice <= 4) {
                final DeviceUpdate lastUpdate =  VirtualCdj.getInstance().getLatestStatusFor(realDevice);
                if (lastUpdate instanceof CdjStatus &&
                        ((CdjStatus) lastUpdate).getTrackSourcePlayer() != targetPlayer) {
                    return candidate.getDeviceNumber();
                }
            }
        }
        throw new IllegalStateException("No player number available to query player " + targetPlayer +
                ". If such a player is present on the network, it must be using Link to play a track from " +
                "our target player, so we can't steal its channel number.");
    }

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running.
     *
     * @return true if we are offering shared dbserver sessions
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }

    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("ConnectionManager does not auto-start when {} starts.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("ConnectionManager stopping because DeviceFinder did.");
                stop();
            }
        }
    };

    /**
     * Finds any clients which are not currently in use, and which have been idle for longer than the
     * idle timeout, and closes them.
     */
    private synchronized void closeIdleClients() {
        List<Client> candidates = new LinkedList<>(openClients.values());
        logger.debug("Scanning for idle clients; {} candidates.", candidates.size());
        for (Client client : candidates) {
            if ((useCounts.get(client) < 1) &&
                    ((timestamps.get(client) + idleLimit.get() * 1000L) <= System.currentTimeMillis())) {
                logger.debug("Idle time reached for unused client {}", client);
                closeClient(client);
            }
        }
    }

    /**
     * Start offering shared dbserver sessions.
     *
     * @throws SocketException if there is a problem opening connections
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws SocketException {
        if (!isRunning()) {
            DeviceFinder.getInstance().addLifecycleListener(lifecycleListener);
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            DeviceFinder.getInstance().start();
            for (DeviceAnnouncement device: DeviceFinder.getInstance().getCurrentDevices()) {
                announcementListener.deviceFound(device);
            }

            new Thread(null, () -> {
                while (isRunning()) {
                    try {
                        //noinspection BusyWait
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted sleeping to close idle dbserver clients");
                    }
                    closeIdleClients();
                }
                logger.info("Idle dbserver client closer shutting down.");
            }, "Idle dbserver client closer").start();

            running.set(true);
            deliverLifecycleAnnouncement(logger, true);
        }
    }

    /**
     * Stop offering shared dbserver sessions.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            running.set(false);
            DeviceFinder.getInstance().removeDeviceAnnouncementListener(announcementListener);
            dbServerPorts.clear();
            for (Client client : openClients.values()) {
                try {
                    client.close();
                } catch (Exception e) {
                    logger.warn("Problem closing {} when stopping", client, e);
                }
            }
            openClients.clear();
            useCounts.clear();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final ConnectionManager ourInstance = new ConnectionManager();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static ConnectionManager getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private ConnectionManager() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ConnectionManager[running:").append(isRunning());
        sb.append(", dbServerPorts:").append(dbServerPorts).append(", openClients:").append(openClients);
        sb.append(", useCounts:").append(useCounts).append(", timestamps:").append(timestamps);
        return sb.append(", idleLimit:").append(idleLimit.get()).append("]").toString();
    }
}
