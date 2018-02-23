package org.adorsys.encobject.userdata;

import java.security.KeyStore;

import javax.crypto.SecretKey;
import javax.security.auth.callback.CallbackHandler;

import org.adorsys.cryptoutils.exceptions.BaseExceptionHandler;
import org.adorsys.encobject.complextypes.BucketPath;
import org.adorsys.encobject.domain.KeyCredentials;
import org.adorsys.encobject.domain.ObjectHandle;
import org.adorsys.encobject.domain.Payload;
import org.adorsys.encobject.exceptions.ObjectNotFoundException;
import org.adorsys.encobject.keysource.KeyCredentialBasedKeySource;
import org.adorsys.encobject.params.KeyParams;
import org.adorsys.encobject.service.BlobStoreKeystorePersistence;
import org.adorsys.encobject.service.EncryptedPersistenceService;
import org.adorsys.encobject.service.EncryptionService;
import org.adorsys.encobject.service.ExtendedStoreConnection;
import org.adorsys.encobject.service.KeystorePersistence;
import org.adorsys.encobject.service.SimplePayloadImpl;
import org.adorsys.encobject.types.KeyID;
import org.adorsys.jkeygen.keystore.KeyStoreService;
import org.adorsys.jkeygen.keystore.SecretKeyData;
import org.adorsys.jkeygen.keystore.SecretKeyEntry;
import org.adorsys.jkeygen.pwd.PasswordCallbackHandler;
import org.adorsys.jkeygen.secretkey.SecretKeyBuilder;

public class ObjectPersistenceAdapter {

    private ObjectMapperSPI objectMapper;
    private KeyCredentials keyCredentials;
    private EncryptedPersistenceService encObjectService;
    private ExtendedStoreConnection storeConnection;
    private KeystorePersistence keystorePersistence;
    private KeyCredentialBasedKeySource keySource;

    public ObjectPersistenceAdapter(EncryptionService encryptionService, ExtendedStoreConnection storeConnection, KeyCredentials keyCredentials, ObjectMapperSPI objectMapper) {
        super();
        this.keyCredentials = keyCredentials;
        this.keySource = new KeyCredentialBasedKeySource(keyCredentials, keystorePersistence);
        this.objectMapper = objectMapper;
        this.storeConnection = storeConnection;
        this.keystorePersistence = new BlobStoreKeystorePersistence(storeConnection);
        this.encObjectService = new EncryptedPersistenceService(storeConnection, encryptionService);
    }

    /**
     * Checks if the user with the given key credential has a store.
     *
     * @return if the given key credential has a store
     */
    public boolean hasStore() {
        return keystorePersistence.hasKeystore(keyCredentials.getHandle());
    }

    /**
     * Initializes the store of the user with the given keyCredentials
     */
    public void initStore() {
        try {
            String container = keyCredentials.getHandle().getContainer();
            if (!storeConnection.containerExists(container)) {
            	storeConnection.createContainer(container);
            }
            newSecretKey(keyCredentials, keyParams());
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }
    }

    public <T> T load(ObjectHandle handle, Class<T> klass) {
        try {
            Payload payload = encObjectService.loadAndDecrypt(BucketPath.fromHandle(handle), keySource);
            return objectMapper.readValue(payload.getData(), klass);
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }
    }

    public <T> void store(ObjectHandle userMainRecordhandle, T t) {
        storeInternal(userMainRecordhandle, t);
    }

    private <T> void storeInternal(ObjectHandle handle, T t) {
    	String container = keyCredentials.getHandle().getContainer();
    	if(!storeConnection.containerExists(container)){
    		storeConnection.createContainer(container);
    	}
        try {
            byte[] data = objectMapper.writeValueAsBytes(t);
            encObjectService.encryptAndPersist(BucketPath.fromHandle(handle), new SimplePayloadImpl(data), keySource, new KeyID(keyCredentials.getKeyid()));
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }

    }

    public KeyCredentials getKeyCredentials() {
        return keyCredentials;
    }

    private static KeyParams keyParams() {
        KeyParams keyParams = new KeyParams();
        keyParams.setKeyAlogirithm("AES");
        keyParams.setKeyLength(256);
        return keyParams;
    }
    
	public void newSecretKey(KeyCredentials keyCredentials, KeyParams keyParams) {
		CallbackHandler storePassHandler = new PasswordCallbackHandler(keyCredentials.getStorepass().toCharArray());
		CallbackHandler secretKeyPassHandler = new PasswordCallbackHandler(keyCredentials.getKeypass().toCharArray());
		
		SecretKey secretKey = new SecretKeyBuilder().withKeyAlg(keyParams.getKeyAlogirithm()).withKeyLength(keyParams.getKeyLength()).build();	
		SecretKeyEntry secretKeyData = SecretKeyData.builder().secretKey(secretKey).alias(keyCredentials.getKeyid()).passwordSource(secretKeyPassHandler).build();

		KeyStore keyStore;
		try {
			keyStore = keystorePersistence.loadKeystore(keyCredentials.getHandle(), storePassHandler);
		} catch (ObjectNotFoundException e) {
			keyStore = KeyStoreService.newKeyStore(null);
		}

		KeyStoreService.addToKeyStore(keyStore, secretKeyData);
		
		keystorePersistence.saveKeyStore(keyStore, storePassHandler, keyCredentials.getHandle());
	}
    
}
