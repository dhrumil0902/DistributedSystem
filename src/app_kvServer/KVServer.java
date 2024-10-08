package app_kvServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

import app_kvECS.ECSClient;
import app_kvServer.kvCache.FIFOCache;
import app_kvServer.kvCache.IKVCache;
import app_kvServer.kvCache.LFUCache;
import app_kvServer.kvCache.LRUCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ecs.ECSNode;
import ecs.IECSNode;
import shared.BST;
import shared.Heartbeat;
import shared.messages.CoordMessage;
import shared.messages.ECSMessage;
import shared.messages.ECSMessage.ActionType;
import shared.messages.KVMessage;
import shared.messages.KVMessage.StatusType;
import shared.messages.KVMessageImpl;
import shared.utils.CommUtils;
import shared.utils.HashUtils;
import logger.LogSetup;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class KVServer implements IKVServer, Runnable {
    /**
     * Start KV Server at given port
     *
     * @param port given port for storage server to operate
     * @param cacheSize specifies how many key-value pairs the server is allowed
     * to keep in-memory
     * @param strategy specifies the cache replacement strategy in case the cache
     * is full and there is a GET- or PUT-request on a key that is
     * currently not contained in the cache. Options are "FIFO", "LRU",
     * and "LFU".
     */
    private static Logger logger = Logger.getRootLogger();
    public final String storageDir;
    private String address;
    private int port;
    public String ecsAddress;
    public int ecsPort;
    int cacheSize;
    CacheStrategy strategy;
    private ServerSocket serverSocket;
    private boolean running;
    public boolean register;
    public String storagePath;
    private IKVCache cache;
    private KVStorage storage;
    private final Object lock = new Object();
    public String serverName;
    private String hashValue;
    public BST metadata;
    private boolean writeLock;
    private List<ClientConnection> clientConnections = new ArrayList<ClientConnection>();
    private List<String> coordinators = new ArrayList<>();
    public List<String> replicationsOfThisServer = new ArrayList<>();
    public Map<String, KVStorage> replicationsStored = new HashMap<>(); //hashvalue and storage
    private HeartbeatServer heartbeat;
    public ECSClient ecsClient;
    public int priorityNum;
    public boolean isLeader;


//    public KVServer(int port, int cacheSize, String strategy) {
//        this(port, cacheSize, strategy, "localhost", System.getProperty("user.dir"));
//    }

    public KVServer(String ecsAddress, int ecsPort, String address, int port, int cacheSize, String strategy,
                    String storageDir) {
        String fileName = address + "_" + port + ".txt";
        this.storagePath = storageDir + File.separator + fileName;
        this.storageDir = storageDir;
        this.ecsAddress = ecsAddress;
        this.ecsPort = ecsPort;
        this.address = address;
        this.port = port;
        this.cacheSize = cacheSize;
        this.writeLock = false;
        this.metadata = null;
        this.register = false;
        this.serverName = address + ":" + port;
        this.hashValue = HashUtils.getHash(serverName);
        this.isLeader = false;
        this.ecsClient = null;
        try {
            this.strategy = CacheStrategy.valueOf(strategy);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid cache strategy value: " + strategy + ". Setting strategy to None.");
            this.strategy = CacheStrategy.None;
        }
        switch (this.strategy) {
            case LRU:
                this.cache = new LRUCache(cacheSize);
                break;
            case LFU:
                this.cache = new LFUCache(cacheSize);
                break;
            case FIFO:
                this.cache = new FIFOCache(cacheSize);
                break;
            default:
                this.cache = new FIFOCache(0);
                break;
        }
        this.storage = new KVStorage(this.storagePath);
        startServer();
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getHostname() {
        return address;
    }

    @Override
    public CacheStrategy getCacheStrategy() {
        return strategy;
    }

    @Override
    public int getCacheSize() {
        return cacheSize;
    }

    public String getStoragePath() {
        return this.storagePath;
    }

    @Override
    public boolean inStorage(String key) {
        try {
            return storage.inStorage(key);
        } catch (RuntimeException e) {
            logger.error("Error: Checking if key is in storage: " + key, e);
            return false;
        }
    }

    @Override
    public boolean inCache(String key) {
        if (cache != null) {
            return cache.inCache(key);
        }
        return false;
    }

    public void addReplicationFile(String hashValue) {
        if (metadata == null || metadata.get(hashValue) == null) {
            logger.error("Metadata is null or does not contain hash value " + hashValue);
            return;
        }

        // Get the node name from metadata using the hash value
        String nodeName = metadata.get(hashValue).getNodeHost() + "-" + metadata.get(hashValue).getNodePort();

        // Check if the hash value already exists in the map
        if (replicationsStored.containsKey(hashValue)) {
            logger.warn("Data file for hash value " + hashValue + " already exists.");
        } else {
            String fileName = this.getPort() + "_" + nodeName + ".txt";
            String path = this.storageDir + File.separator + fileName;
            KVStorage storage = new KVStorage(path);
            replicationsStored.put(hashValue, storage);
            logger.info("Added replication data file for node " + nodeName + " at " + path);
        }
    }

    public List<String> removeReplicationFileAndGetAllData(String hashValue) {
        // Check if the hash value exists in the map
        if (replicationsStored.containsKey(hashValue)) {
            KVStorage storage = replicationsStored.get(hashValue);
            if (storage != null) {
                // Get all data from the storage
                List<String> allData = null;
                try {
                    allData = storage.getAllData();
                } catch (IOException e) {
                    logger.error("Unable to get all data from the storage.");
                    return new ArrayList<>();
                }
                KVStorage removedStorage = replicationsStored.remove(hashValue);
                if (removedStorage != null) {
                    logger.info("Removed replication data file for hash value " + hashValue);
                    return allData;
                } else {
                    logger.warn("Failed to remove replication data file for hash value " + hashValue);
                }
            } else {
                logger.warn("Replication data file for hash value " + hashValue + " is null.");
            }
        } else {
            logger.warn("Replication data file for hash value " + hashValue + " does not exist.");
        }
        return null; // Return null if removal fails or if hash value does not exist in the map
    }

    @Override
    public String getKV(String key) throws Exception {
        logger.info("SERVER: Retrieve value for key: " + key);
        String value;
        if (cache != null && cache.inCache(key)) {
            value = cache.getKV(key);
        } else {
            try {
                value = storage.getKV(key);
                logger.info(String.format("Key: %s; Value: %s", key, value));
            } catch (RuntimeException e) {
                throw new Exception(String.format("Error retrieving Key: %s from storage。 %s", key, e.getMessage()));
            }
            if (cacheSize != 0 || value != null) {
                SimpleEntry<String, String> evicted = cache.putKV(key, value);
                if (evicted != null) {
                    try {
                        writeEvictedToStorage(evicted.getKey(), evicted.getValue());
                    } catch (RuntimeException e) {
                        throw new Exception(e.getMessage());
                    }
                }
                try {
                    storage.deleteKV(key);
                } catch (RuntimeException e) {
                    throw new Exception(String.format("Error deleting old Key: %s from storage. %s", key, e.getMessage()));
                }
            }

        }
        return value;
    }

    @Override
    public void putKV(String key, String value) throws Exception {
        logger.info("Storage dir: " + getStoragePath());
        logger.info(String.format("PutKV: %s %s", key, value));
        // Put kv to storage
        if (cache == null) {
            try {
                storage.putKV(key, value);
                return;
            } catch (RuntimeException e) {
                throw new Exception(e.getMessage());
            }
        }
        // Put kv to cache
        SimpleEntry<String, String> evicted = cache.putKV(key, value);
        if (evicted != null) {
            try {
                writeEvictedToStorage(evicted.getKey(), evicted.getValue());
            } catch (RuntimeException e) {
                throw new Exception(e.getMessage());
            }
        }
    }

    public void putKVForReplica(String key, String value, String hashValue) throws Exception {
        logger.info("Storage file path: " + storage.filePath);
        logger.info(String.format("PutKV: %s %s", key, value));

        if (!replicationsStored.containsKey(hashValue)) {
            // addReplicationFile(hashValue);
        }
        KVStorage replicaStorage = replicationsStored.get(hashValue);
        // Put kv to storage
        if (cache == null) {
            try {
                replicaStorage.putKV(key, value);
                return;
            } catch (RuntimeException e) {
                throw new Exception(e.getMessage());
            }
        }
        // Put kv to cache
        SimpleEntry<String, String> evicted = cache.putKV(key, value);
        if (evicted != null) {
            try {
                writeEvictedToStorage(evicted.getKey(), evicted.getValue());
            } catch (RuntimeException e) {
                throw new Exception(e.getMessage());
            }
        }
    }

    public boolean deleteKV(String key) throws Exception {
        if (inCache(key)) {
            cache.deleteKV(key);
        } else if (inStorage(key)) {
            try {
                storage.deleteKV(key);
            } catch (RuntimeException e) {
                throw new Exception(e);
            }
        } else {
            return false;
        }
        return true;
    }

    public void updateStorage(String key, String value) throws Exception {
        // Update kv to storage
        if (cache == null) {
            try {
                storage.updateKV(key, value);
                return;
            } catch (RuntimeException e) {
                throw new Exception(e);
            }
        }
        // Update kv to cache
        SimpleEntry<String, String> evicted = cache.putKV(key, value);
        if (evicted != null) {
            try {
                writeEvictedToStorage(evicted.getKey(), evicted.getValue());
            } catch (RuntimeException e) {
                throw new Exception(e.getMessage());
            }
        }
        // Delete old kv from storage
        try {
            storage.deleteKV(key);
        } catch (RuntimeException e) {
            throw new Exception(String.format("Error deleting old Key: %s from storage. %s", key, e.getMessage()));
        }
    }

    public void appendDataToStorage(List<String> data) {
        storage.putList(data);
    }

    public List<String> getAllData() {
        try {
            return storage.getAllData();
        } catch (IOException e) {
            logger.error("Unable to retrieve data from storage", e);
            return null;
        }
    }

    public List<String> getData(String minVal, String maxVal) {
        try {
            return storage.getData(minVal, maxVal);
        } catch (IOException e) {
            logger.error("Unable to retrieve data from storage", e);
            return null;
        }
    }

    public boolean removeData(String minVal, String maxVal) {
        logger.info("In removeData function in: " + port);
        try {
            storage.removeData(minVal, maxVal);
            return true;
        } catch (IOException e) {
            logger.error("Unable to remove data from storage", e);
            return false;
        }
    }

    @Override
    public void clearCache() {
        if (cache != null) {
            cache.clearCache();
        }
    }

    @Override
    public void clearStorage() {
        try {
            storage.clearStorage();
        } catch (RuntimeException e) {
            logger.error(e);
        }
    }

    private void writeEvictedToStorage(String key, String value) throws RuntimeException {
        try {
            storage.putKV(key, value);
        } catch (RuntimeException e) {
            throw new RuntimeException(String.format("Error writing evicted Key %s to disk. %s", key, e.getMessage()));
        }
    }

    public void syncCacheToStorage() {
        if (cacheSize != 0) {
            for (Map.Entry<String, String> entry : cache.getStoredData().entrySet()) {
                try {
                    storage.putKV(entry.getKey(), entry.getValue());
                } catch (RuntimeException e) {
                    logger.error("Failed to sync cache.", e);
                }
            }
        }
    }

    public void startServer() {
        new Thread(this).start();
    }

    @Override
    public void run() {
        running = initializeServer();
        new Thread(new Runnable() {
            @Override
            public void run() {
                connectToCentralServer();
            }
        }).start();
        if (serverSocket != null) {
            while (isRunning()) {
                try {
                    if (heartbeat == null) {
                        heartbeat = new HeartbeatServer(this);
                        new Thread(() -> {
                            heartbeat.start();
                        }).start();
                    }
                    Socket client = serverSocket.accept();
                    ClientConnection connection = new ClientConnection(client, this);
                    clientConnections.add(connection);
                    new Thread(connection).start();

                    logger.info(
                            "From Server: Connected to " + client.getInetAddress().getHostName() + " on port " + client.getPort());
                } catch (IOException e) {
                    logger.info(String.format("%s: client disconnect from server socket.", serverName));
                }
            }
        }
        logger.info("Server stopped.");
    }

    @Override
    public void kill() {
        logger.info(String.format("%s: Killing server.", serverName));
        running = false;
        try {
            serverSocket.close();
            for (ClientConnection connection : clientConnections) {
                connection.close();
            }
        } catch (IOException e) {
            logger.error("Error! " + "Unable to close socket on port: " + port, e);
        }
    }

    @Override
    public void close() {
        logger.info("Closing server.");
        running = false;
        syncCacheToStorage();
        try {
            disconnectFromCentralServer();
            serverSocket.close();
            for (ClientConnection connection : clientConnections) {
                connection.close();
            }
        } catch (IOException e) {
            logger.error("Error! " +
                    "Unable to close socket on port: " + port, e);
        }
    }

    private boolean initializeServer() {
        logger.info("Initialize server ...");
        try {
            InetSocketAddress socketAddress = new InetSocketAddress(address, port);
            serverSocket = new ServerSocket();
            serverSocket.bind(socketAddress);
            logger.info("Server listening on port: " + serverSocket.getLocalPort());
            return true;
        } catch (IOException e) {
            logger.error("Error! Cannot open server socket:");
            if (e instanceof BindException) {
                logger.error("Port " + port + " is already bound!");
            }
            return false;
        }
    }

    private boolean isRunning() {
        return this.running;
    }

    private void connectToCentralServer() {
//        if (ECSAddress.isEmpty() || ecsPort == -1) {
//            return;
//        }
        try (Socket ECSSocket = new Socket(ecsAddress, ecsPort)) {
            // Setup input and output streams
            logger.info("Send connection request to ECS.");
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(ECSSocket.getOutputStream(), StandardCharsets.UTF_8));
            ECSMessage msg = new ECSMessage();
            msg.setAction(ActionType.NEW_NODE);
            msg.setServerInfo(address, port);

            ObjectMapper mapper = new ObjectMapper();
            try {
                String jsonString = mapper.writeValueAsString(msg);
                out.write(jsonString);
                out.newLine();
                out.flush();
            } catch (JsonProcessingException e) {
                logger.error("Failed to parse ECSMessage.");
            } catch (IOException e) {
                logger.error("Failed to send ECSMessage.");
            }
        } catch (IOException e) {
            logger.error("Failed to connect to the central server.", e);
        }
    }

    private void disconnectFromCentralServer() {

        try (Socket ECSSocket = new Socket(ecsAddress, ecsPort)) {
            // Setup input and output streams
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(ECSSocket.getOutputStream(), StandardCharsets.UTF_8));
            ECSMessage msg = new ECSMessage();
            msg.setData(getAllData());
            msg.setAction(ActionType.DELETE);
            msg.setServerInfo(address, port);
            if (metadata.size() > 1) {
                logger.info("Removing all the data from: " + port);
                removeData("00000000000000000000000000000000", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
            }
            ObjectMapper mapper = new ObjectMapper();
            try {
                String jsonString = mapper.writeValueAsString(msg);
                out.write(jsonString);
                out.newLine();
                out.flush();
            } catch (JsonProcessingException e) {
                logger.error("Failed to parse ECSMessage.");
            } catch (IOException e) {
                logger.error("Failed to send ECSMessage.");
            }
            logger.info("Informing ECSClient to delete server: " + port);
        } catch (IOException e) {
            logger.error("Failed to connect to the central server.", e);
        }
    }


    public boolean checkKeyRange(String key) {
        logger.info(">>>>>");
        logger.info("Target server name: " + metadata.getNodeFromKey(HashUtils.getHash(key)).getNodeName());
        logger.info("Current server name: " + address + ":" + port);
        return metadata.getNodeFromKey(HashUtils.getHash(key)).getNodeName().equals(this.serverName);
    }

    public KVMessage handleGetMessage(KVMessage message) {
        String key = message.getKey();
        KVMessage response = new KVMessageImpl();
        if (checkKeyRange(key)) {
            response.setKey(key);
            synchronized (lock) {
                try {
                    logger.info("SERVER: Trying to GET the value associated with Key '" + key);
                    String value = getKV(key);
                    if (value == null || value.isEmpty()) {
                        response.setStatus(StatusType.GET_ERROR);
                        return response;
                    }
                    response.setStatus(StatusType.GET_SUCCESS);
                    response.setValue(value);
                } catch (Exception e) {
                    logger.error("Error retrieving value for key '" + key + "': " + e.getMessage());
                    response.setStatus(StatusType.GET_ERROR);
                    return response;
                }
            }
        } else if (checkKeyRangeForReplicas(key)) {
            response.setKey(key);
            synchronized (lock) {
                try {
                    logger.info("SERVER: Trying to GET the value from replicas associated with Key '" + key);
                    String nodeHash = metadata.getNodeFromKey(HashUtils.getHash(key)).getNodeHashRange()[1];
                    String value = null;
                    if (nodeHash != null) {
                        value = replicationsStored.get(nodeHash).getKV(key);
                    }

                    if (value == null || value.isEmpty()) {
                        response.setStatus(StatusType.GET_ERROR);
                        return response;
                    }
                    response.setStatus(StatusType.GET_SUCCESS);
                    response.setValue(value);
                } catch (Exception e) {
                    logger.error("Error retrieving value for key '" + key + "': " + e.getMessage());
                    response.setStatus(StatusType.GET_ERROR);
                    return response;
                }
            }
        } else {
            response.setStatus(StatusType.SERVER_NOT_RESPONSIBLE);
            response.setMetadata(this.metadata);
        }
        return response;

    }

    private boolean checkKeyRangeForReplicas(String key) {
        String nodeHash = metadata.getNodeFromKey(HashUtils.getHash(key)).getNodeHashRange()[1];
        if (nodeHash != null) {
            return replicationsStored.containsKey(nodeHash);
        }
        return false;
    }

    private String getKVFromReplicas(String key) {
        for (KVStorage replicaStorage : replicationsStored.values()) {
            String value = replicaStorage.getKV(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    public KVMessage handlePutMessage(KVMessage message) {
        String key = message.getKey();
        KVMessage response = new KVMessageImpl();
        if (getWriteLock()) {
            response.setStatus(StatusType.SERVER_WRITE_LOCK);
            return response;
        }
        if (checkKeyRange(key)) {
            logger.info("In range key");
            response.setKey(key);
            synchronized (lock) {
                logger.debug("Got the Lock");
                String value = message.getValue();
                // Delete
                if (value.equals("null")) {
                    try {
                        logger.info("SERVER: Key '" + key + "' deleted '");
                        if (!deleteKV(key)) {
                            logger.info(String.format("SERVER: Delete key %s not exists.", key));
                            response.setStatus(StatusType.DELETE_ERROR);
                            return response;
                        }
                        response.setStatus(StatusType.DELETE_SUCCESS);
                        return response;
                    } catch (Exception e) {
                        logger.error("Error deleting key '" + key + " " + e.getMessage());
                        response.setStatus(StatusType.DELETE_ERROR);
                        return response;
                    }
                }
                response.setValue(value);
                // Update
                if (inCache(key)) {
                    logger.info("SERVER: Update cache with Key '" + key + "', Value '" + value + "'");
                    cache.updateKV(key, value);
                    response.setStatus(StatusType.PUT_UPDATE);
                    return response;
                }
                if (inStorage(key)) {
                    logger.info("SERVER: Update storage with Key '" + key + "', Value '" + value + "'");
                    try {
                        updateStorage(key, value);
                        response.setStatus(StatusType.PUT_UPDATE);
                        return response;
                    } catch (Exception e) {
                        logger.error("Error updating key-value pair for key '" + key + "': " + e.getMessage());
                        response.setStatus(StatusType.PUT_ERROR);
                        return response;
                    }
                }

                //Put
                logger.info("SERVER: PUT  Key '" + key + "', Value '" + value + "'");
                try {
                    putKV(key, value);
                    response.setStatus(StatusType.PUT_SUCCESS);
                } catch (Exception e) {
                    logger.error("Error putting key-value pair for key '" + key + "': " + e.toString());
                    response.setStatus(StatusType.PUT_ERROR);
                    return response;
                }
            }
        } else {
            response.setStatus(StatusType.SERVER_NOT_RESPONSIBLE);
        }
        return response;
    }

    public KVMessage handleKeyRangeMessage(KVMessage msg) {
        KVMessage message = new KVMessageImpl();
        message.setMetadata(metadata);
        if (message.getMetadata() == null) {
            message.setStatus(KVMessage.StatusType.SERVER_STOPPED);
        } else {
            message.setStatus(KVMessage.StatusType.KEYRANGE_SUCCESS);
        }
        return message;
    }

    public KVMessage handleKeyRangeReadMessage(KVMessage msg) {
        KVMessage message = new KVMessageImpl();
        message.setMetadata(metadata.createReplicatedRange());
        if (message.getMetadata() == null) {
            message.setStatus(KVMessage.StatusType.KEYRANGE_ERROR);
        } else {
            message.setStatus(KVMessage.StatusType.KEYRANGE_SUCCESS);
        }
        return message;
    }

    public void updateMetadata(BST metadata) {
        // Log success message only if this is the initial setup
        if (this.metadata == null) {
            logger.info("Register to ECS success.");
            this.register = true; // Mark as registered only during the initial setup
        }

        // Common operations for both initial setup and subsequent updates
        ECSNode curNode = (ECSNode) metadata.get(hashValue);
        if (curNode != null) { // Adding null check for safety
            this.priorityNum = curNode.priorityNum;
        }

        this.metadata = metadata;
        updateReplicaInfo();
    }

    private synchronized void updateReplicaInfo() {
        ECSNode node = (ECSNode) metadata.get(this.getHashValue());
        List<String> previousReplicationsOfThisServer = replicationsOfThisServer;
        replicationsOfThisServer = node.successors;
        for (String hashofPredecessors : node.predecessors) {
            if (!replicationsStored.containsKey(hashofPredecessors)) {
                addReplicationFile(hashofPredecessors);
            }
        }
        synchronized (this) {
            Set<String> keys = this.replicationsStored.keySet();
            Iterator<String> iterator = keys.iterator();
            while (iterator.hasNext()) {
                String hashofReplicationStorage = iterator.next();
                if (!node.predecessors.contains(hashofReplicationStorage)) {
                    //removeReplicationFileAndGetAllData(hashofReplicationStorage);
                    replicationsStored.get(hashofReplicationStorage).removeAllData();
                    try {
                        Files.delete(replicationsStored.get(hashofReplicationStorage).filePath);
                    } catch (IOException e) {
                        logger.error("Unable to delete file: " + replicationsStored.get(hashofReplicationStorage).filePath);
                    }
                    iterator.remove();
                    replicationsStored.remove(hashofReplicationStorage);
                }
            }
        }
        // add if condition, if time permits to fix bug
        //if(!replicationsOfThisServer.equals(previousReplicationsOfThisServer)){
        CoordMessage msg = new CoordMessage(this.getHashValue());
        msg.setAction(CoordMessage.ActionType.FORCE_SYNC);
        msg.nodes = this.metadata;
        try {
            msg.setData(storage.getAllData());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        updateReplica(msg);
        //}
    }

    public boolean getWriteLock() {
        return this.writeLock;
    }

    public void setWriteLock(boolean flag) {
        this.writeLock = flag;
    }

    public BST getMetadata() {
        return this.metadata;
    }

    public String getHashValue() {
        return this.hashValue;
    }

    public int getPriorityNum() {return this.priorityNum;}

    public void setCoordinators(ECSNode node) {
        for (String predecessor : node.getPredecessors()) {
            ECSNode nodePredeseccor = (ECSNode) metadata.get(predecessor);
            //this.coordinators.add(new AddressPortPair(nodePredeseccor.getNodeHost(), nodePredeseccor.getNodePort()));
        }
    }

    public void setReplicationsOfThisServer(ECSNode node) {
        for (String predecessor : node.getSuccessors()) {
            ECSNode nodePredeseccor = (ECSNode) metadata.get(predecessor);
            //his.coordinators.add(new AddressPortPair(nodePredeseccor.getNodeHost(), nodePredeseccor.getNodePort()));
        }
    }

    public void updateReplica(CoordMessage message) {
        for (String hashValofReplica : this.replicationsOfThisServer) {
            ECSNode replicaInfo = (ECSNode) metadata.get(hashValofReplica);
            try (Socket socket = new Socket(replicaInfo.getNodeHost(), replicaInfo.getNodePort());
                 BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            ) {
                CommUtils.sendCoordMessage(message, output);
                logger.info("Successfully update replicas.");
            } catch (UnknownHostException e) {
                logger.error(String.format("Unknown replica %s:%s", replicaInfo.getNodeHost(), replicaInfo.getNodePort()));
            } catch (IOException e) {
                logger.error(String.format("Failed to connect to %s:%s", replicaInfo.getNodeHost(), replicaInfo.getNodePort()));
            }
        }
    }

    public boolean checkRegisterStatus() {
        return this.register;
    }

    private static String generateHelpString() {
        return "Usage: java KVServer [-p <port>] [-a <address>] [-d <directory>] [-l <logFile>] [-ll <logLevel>] [-c <cacheSize>] [-cs <cacheStrategy>]\n"
                + "Options:\n"
                + "  -b <address:port>  Address and port number of the ECS server (default: localhost:5001)\n"
                + "  -p <port>          Port number for the KVServer (default: 5000)\n"
                + "  -a <address>       Address for the KVServer (default: localhost)\n"
                + "  -d <directory>     Directory for storage (default: current directory)\n"
                + "  -l <logFile>       File path for the log file (default: ./server.log)\n"
                + "  -ll <logLevel>     Log level for the server (default: ALL)\n"
                + "  -c <cacheSize>     Size of the cache (default: 10)\n"
                + "  -cs <cacheStrategy> Cache replacement strategy (default: None)\n\n"
                + "Example:\n"
                + "  java KVServer -p 8080 -a 127.0.0.1 -d /path/to/data -l /path/to/server.log -ll INFO -c 50 -cs LRU";
    }

    public static void main(String[] args) {

        String helpString = generateHelpString();

        // Default Values
        int port = 5000;
        String address = "localhost";
        int ecsPort = 5100;
        String ecsAddress = "localhost";
        String directory = System.getProperty("user.dir");
        String logFile = String.format("logs/%s_%d.log", address, port);
        Level logLevel = Level.ALL;
        CacheStrategy strategy = CacheStrategy.None;
        int cacheSize = 10;

        if (args.length > 0 && args[0].equals("-h")) {
            System.out.println(helpString);
            System.exit(1);
        }

//        if (args.length % 2 != 0) {
//            System.out.println("Invalid number of arguments");
//            System.out.println(helpString);
//            System.exit(1);
//        }

        try {
            for (int i = 0; i < args.length; i += 2) {
                switch (args[i]) {
                    case "-b":
                        String[] pair = args[i + 1].split(":");
                        ecsAddress = pair[0];
                        ecsPort = Integer.parseInt(pair[1]);
                        System.out.println(ecsAddress + " " + ecsPort);
                        break;
                    case "-p":
                        port = Integer.parseInt(args[i + 1]);
                        break;
                    case "-a":
                        address = args[i + 1];
                        break;
                    case "-d":
                        directory = args[i + 1];
                        break;
                    case "-l":
                        logFile = args[i + 1];
                        break;
                    case "-ll":
                        if (LogSetup.isValidLevel(args[i + 1]))
                            logLevel = Level.toLevel(args[i + 1]);
                        else {
                            System.out.println("Invalid log level: " + args[i + 1]);
                            System.out.println(helpString);
                            System.exit(1);
                        }
                        break;
                    case "-c":
                        cacheSize = Integer.parseInt(args[i + 1]);
                        break;
                    case "-s":
                        strategy = CacheStrategy.valueOf("None");
                        break;
                    default:
                        System.out.println("Invalid argument: " + args[i]);
                }
            }
        } catch (NumberFormatException nfe) {
            System.out.println("Error! Invalid argument -p! Not a number!");
            System.out.println(helpString);
            System.exit(1);
        } catch (IllegalArgumentException iae) {
            System.out.println("Error! Invalid argument -cs! Not a valid cache strategy!");
            System.out.println(helpString);
            System.exit(1);
        } catch (Exception e) {
            System.out.println("Unexpected error:\n" + e.getMessage());
            System.out.println(helpString);
            System.exit(1);
        }

        try {
            new LogSetup(logFile, logLevel);
            final KVServer server = new KVServer(ecsAddress, ecsPort, address, port, cacheSize, strategy.toString(), directory);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    server.close();
                }
            });
        } catch (IOException e) {
            System.out.println("Error! Unable to initialize logger!");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("Unexpected error:\n" + e.getMessage());
            System.exit(1);
        }
    }

    public ECSMessage sendMessage(String host, int port, ECSMessage msg) throws Exception {
        try (Socket ECSSocket = new Socket(host, port)) {
            // Setup input and output streams
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(ECSSocket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(ECSSocket.getInputStream(), StandardCharsets.UTF_8));

            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(msg);

            logger.info("Send message: " + jsonString);

//            out.writeObject(msg);
            out.write(jsonString);
            out.newLine();
            out.flush();

            // Wait for a response from the central server
            try {
                String response = in.readLine();
                return new ObjectMapper().readValue(response, ECSMessage.class);
            } catch (JsonMappingException ex) {
                logger.error("Error during message deserialization.", ex);
            }
        } catch (Exception ex) {
            logger.error("While trying to receive/send message received error");
        }
        return null;
    }

    public synchronized void sendHeartbeats() {
        logger.info(String.format("%s: Send heartbeat to %s:%s", serverName, ecsAddress, ecsPort));
        if (ecsAddress == null || ecsAddress.isEmpty() || ecsPort == 0 || metadata == null) {
            logger.info("ECS client info not set yet " + this.getPort());
            return;
        }
        try {
            ECSMessage heartbeatMsg = new ECSMessage(ActionType.HEARTBEAT, true, null, null, null);
            ECSMessage response = this.sendMessage(ecsAddress, ecsPort, heartbeatMsg);
            if (response == null || !response.success) {
                logger.info("Failed to receive heartbeat response from ecsclient");
                initiateElection();
//                if (((ECSNode) metadata.get(this.getHashValue())).priorityNum == metadata.getMaxPriorityNum()) {
//                    onECSClientDown();
//                }
            } else {
                logger.info("Received heartbeat response from ecsclient ");
            }
        } catch (Exception e) {
            throw new RuntimeException("Not good!");
            // logger.error("Error sending heartbeat to ecsclient");
        }
    }

    public void initiateElection() {
        logger.info(String.format("%s: Start leader election.", this.serverName));
        logger.info(String.format("%s: priority number: %s", serverName, priorityNum));
        synchronized (this) {
            if (!isLeader) {
                boolean success = true;
                for (ECSNode node: metadata.values()) {
                    if (node.priorityNum > this.priorityNum) {
                        // Broadcast election message to nodes with higher ID
                        ECSMessage electionMsg = new ECSMessage();
                        electionMsg.setSenderID(this.priorityNum);
                        electionMsg.setAction(ActionType.ELECTION);
                        ECSMessage response = sendMessage(node, electionMsg);
                        if (response != null & response.getAction() == ActionType.ELECTION & response.success) {
                            success = false;
                        }
                    }
                }
                logger.info(String.format("%s: Finish leader election.", this.serverName));
                if (success) {
                    onECSClientDown();
                }
            }
        }
    }

    private void declareVictory() {

    }

    private void onECSClientDown() {
        if (!isLeader) {
            isLeader = true;
        } else {
            logger.info(String.format("%s: Already the leader.", serverName));
            return;
        }
        logger.info(String.format("%s: Transform to ECSClient...", serverName));
        this.heartbeat.stop();
        ECSNode removeNode = (ECSNode) metadata.get(this.hashValue);
        String removeNodeHash = removeNode.getNodeHashRange()[1];

        if (metadata.size() == 1) {
            metadata.delete(removeNodeHash);
           // return;
        }
        else {
            String hashOfSuccessor = getSuccessor(removeNodeHash);
            ECSNode successorNode = (ECSNode) metadata.get(hashOfSuccessor);
            sendMessage(successorNode, new ECSMessage(ActionType.SET_WRITE_LOCK, true, null, null, metadata));
            sendMessage(successorNode, new ECSMessage(ActionType.INTERNAL_TRANSFER, true, null, null, metadata, removeNodeHash));
            sendMessage(successorNode, new ECSMessage(ActionType.UNSET_WRITE_LOCK, true, null, null, metadata));
            successorNode.getNodeHashRange()[0] = removeNode.getNodeHashRange()[0];
            metadata.delete(removeNodeHash);
            storage.removeAllData();
        }
        this.kill();
        logger.info("Removed a node from the bst, current state of bst: " + metadata.print());
        ecsClient = new ECSClient(this.address, this.port, metadata);
        logger.info(String.format("%s: Transform to ECSClient finish.", serverName));
    }

    public String getSuccessor(String startingHash) {
        if (metadata.isEmpty()) {
            return null;
        }

        if (metadata.successor(startingHash) != null) {
            return metadata.successor(startingHash);
        }

        return metadata.min();
    }

    public ECSMessage sendMessage(ECSNode node, ECSMessage msg) {
        try (Socket socket = new Socket()) {
//            int timeout = 5000;
//            socket.connect(new InetSocketAddress(node.getNodeHost(), node.getNodePort()), timeout);
            socket.connect(new InetSocketAddress(node.getNodeHost(), node.getNodePort()));
            // Setup input and output streams
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(msg);

            logger.info("Send message: " + jsonString);

            out.write(jsonString);
            out.newLine();
            out.flush();

            // Wait for a response from the central server
            String response = in.readLine();
            try {
                return new ObjectMapper().readValue(response, ECSMessage.class);
            } catch (JsonMappingException ex) {
                logger.error("Error during message deserialization.", ex);
            }
        } catch (SocketTimeoutException ex) {
            logger.error("The socket operation timed out.", ex);
        } catch (Exception ex) {
            logger.error("While trying to receive/send message received error");
        }
        return null;
    }
}
