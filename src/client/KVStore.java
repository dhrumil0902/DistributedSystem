package client;

import org.apache.log4j.Logger;

import ecs.IECSNode;
import shared.BST;
import shared.messages.KVMessage;
import shared.messages.KVMessageImpl;
import shared.utils.HashUtils;

public class KVStore implements KVCommInterface {
	private Logger logger = Logger.getRootLogger();

	private String address;
	private int port;
	private String nodeName;
	private BST metadata;
	
	private final CommManager commManager;
	
	/**
	 * Initialize KVStore with address and port of KVServer
	 * @param address the address of the KVServer
	 * @param port the port of the KVServer
	 */
	public KVStore(String address, int port) {
		this.address = address;
		this.port = port;
		commManager = new CommManager();
	}

	@Override
	public void connect() throws Exception {
		if (commManager.isConnected()) {
			throw new Exception("Already connected to a server");
		}
		commManager.connect(address, port);
		updateMetadata();
		nodeName = address + ":" + port;
	}

	@Override
	public void disconnect() {
		commManager.disconnect();
	}
	
	@Override
	public KVMessage put(String key, String value) throws Exception {
		String request = "put " + key + " " + value;
		setServerForKey(key);
		KVMessage responseMessage = sendRequest(request);
		if (responseMessage.getStatus() == KVMessage.StatusType.SERVER_NOT_RESPONSIBLE) {
			updateMetadata();
			setServerForKey(key);
			responseMessage = sendRequest(request);
		}
		return responseMessage;
		
	}

	@Override
	public KVMessage get(String key) throws Exception {
		String request = "get " + key;
		setRandomServerForKey(key);
		KVMessage responseMessage = sendRequest(request);
		if (responseMessage.getStatus() == KVMessage.StatusType.SERVER_NOT_RESPONSIBLE) {
			updateMetadata();
			setRandomServerForKey(key);
			responseMessage = sendRequest(request);
		}
		return responseMessage;
	}

	public String getAddress() {
		return address;
	}

	public int getPort() {
		return port;
	}

	public boolean isConnected() {
		return commManager.isConnected();
	}

	public String getServerName() {
		return nodeName;
	}

	private KVMessage sendRequest(String request) throws Exception {
		String response = commManager.sendMessage(request);
		KVMessage responseMessage =  KVMessageImpl.fromString(response);
		logger.info("Received message: " + responseMessage.getStatus());
		if (responseMessage.getStatus() == KVMessage.StatusType.DISCONNECT) {
//			metadata.delete(HashUtils.getHash(nodeName));
			metadata = responseMessage.getMetadata();
			logger.info("Client: Disconnect from server and update metadata.");
			disconnect();
			if(!metadata.isEmpty()) {
				logger.info("Client: Reconnect to existing server...");
				IECSNode node = metadata.getNodeFromKey(HashUtils.getHash(nodeName));
				this.address = node.getNodeHost();
				this.port = node.getNodePort();
				this.nodeName = node.getNodeName();
				connect();
				responseMessage = sendRequest(request);
			} else {
				disconnect();
				throw new Exception("No servers available");
			}
		}
		return responseMessage;
	}

	public void updateMetadata() throws Exception {
		keyrange();
	}

	private void keyrange() throws Exception {
		KVMessage metadataMessage = sendRequest("keyrange");
		if (metadataMessage.getStatus() != KVMessage.StatusType.KEYRANGE_SUCCESS)
			throw new Exception("Keyrange query failed");
		metadata = metadataMessage.getMetadata();
	}

	private void keyrange_read() throws Exception {
		KVMessage metadataMessage = sendRequest("keyrange_read");
		if (metadataMessage.getStatus() != KVMessage.StatusType.KEYRANGE_READ_SUCCESS)
			throw new Exception("Keyrange read query failed");
		metadata = metadataMessage.getMetadata();
	}

	private void setServerForKey(String key) throws Exception {
		String hashedKey = HashUtils.getHash(key);
		if (metadata.isEmpty()) {
            return;
        }
		
		IECSNode node = metadata.getNodeFromKey(hashedKey);

		if (!node.getNodeName().equals(nodeName)) {
			disconnect();
			this.address = node.getNodeHost();
			this.port = node.getNodePort();
			this.nodeName = node.getNodeName();
			connect();
		}
	}

	private void setRandomServerForKey(String key) throws Exception {
		String hashedKey = HashUtils.getHash(key);
		if (metadata.isEmpty()) {
            return;
        }
		
		IECSNode node = metadata.getRandomNodeForKey(hashedKey);

		if (!node.getNodeName().equals(nodeName)) {
			disconnect();
			this.address = node.getNodeHost();
			this.port = node.getNodePort();
			this.nodeName = node.getNodeName();
			connect();
		}
	}
}