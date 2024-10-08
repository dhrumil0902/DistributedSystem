package shared.messages;

import shared.BST;

public interface KVMessage {

	public enum StatusType {
		GET, 			/* Get - request */
		GET_ERROR, 		/* requested tuple (i.e. value) not found */
		GET_SUCCESS, 	/* requested tuple (i.e. value) found */
		PUT, 			/* Put - request */
		PUT_SUCCESS, 	/* Put - request successful, tuple inserted */
		PUT_UPDATE, 	/* Put - request successful, i.e. value updated */
		PUT_ERROR, 		/* Put - request not successful */
		DELETE_SUCCESS, /* Delete - request successful */
		DELETE_ERROR, 	/* Delete - request successful */
		SERVER_NOT_RESPONSIBLE,
		SERVER_WRITE_LOCK,
		SERVER_STOPPED,
		KEYRANGE,
		KEYRANGE_READ,
		KEYRANGE_ERROR,
		KEYRANGE_SUCCESS,
		KEYRANGE_READ_SUCCESS,
		DISCONNECT
	}

	/**
	 * @return the key that is associated with this message, 
	 * 		null if not key is associated.
	 */
	public String getKey();

	public void setKey(String key);

	/**
	 * @return the value that is associated with this message, 
	 * 		null if not value is associated.
	 */
	public String getValue();

	public void setValue(String value);

	/**
	 * @return a status string that is used to identify request types, 
	 * response types and error types associated to the message.
	 */
	public StatusType getStatus();

	public void setStatus(StatusType status);

	public void setMetadata(BST metadata);

	public BST getMetadata();
}


