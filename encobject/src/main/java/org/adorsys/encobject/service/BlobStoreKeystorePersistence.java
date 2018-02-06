package org.adorsys.encobject.service;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Map;

import javax.security.auth.callback.CallbackHandler;

import org.adorsys.cryptoutils.exceptions.BaseExceptionHandler;
import org.adorsys.encobject.complextypes.KeyStoreLocation;
import org.adorsys.encobject.domain.ObjectHandle;
import org.adorsys.encobject.domain.Tuple;
import org.adorsys.encobject.domain.keystore.KeystoreData;
import org.adorsys.encobject.exceptions.ExtendedPersistenceException;
import org.adorsys.encobject.types.KeyStoreType;
import org.adorsys.jkeygen.keystore.KeyStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

/**
 * Service in charge of loading and storing user keys.
 * 
 * @author fpo
 *
 */
public class BlobStoreKeystorePersistence implements KeystorePersistence {
	private final static Logger LOGGER = LoggerFactory.getLogger(BlobStoreKeystorePersistence.class);

	private ExtendedStoreConnection extendedStoreConnection;

	public BlobStoreKeystorePersistence(ExtendedStoreConnection extendedStoreConnection) {
		this.extendedStoreConnection = extendedStoreConnection;
	}

	public void saveKeyStore(KeyStore keystore, CallbackHandler storePassHandler, ObjectHandle handle) throws NoSuchAlgorithmException, CertificateException, UnknownContainerException{
		try {
			String storeType = keystore.getType();
			byte[] bs = KeyStoreService.toByteArray(keystore, handle.getName(), storePassHandler);
			KeystoreData keystoreData = KeystoreData.newBuilder().setType(storeType).setKeystore(ByteString.copyFrom(bs)).build();
			extendedStoreConnection.putBlob(handle, keystoreData.toByteArray());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public void saveKeyStoreWithAttributes(KeyStore keystore, Map<String, String> attributes, CallbackHandler storePassHandler, ObjectHandle handle) throws NoSuchAlgorithmException, CertificateException, UnknownContainerException{
		try {
			String storeType = keystore.getType();
			byte[] bs = KeyStoreService.toByteArray(keystore, handle.getName(), storePassHandler);
			KeystoreData keystoreData = KeystoreData.newBuilder()
					.setType(storeType)
					.setKeystore(ByteString.copyFrom(bs))
					.putAllAttributes(attributes)
					.build();
			extendedStoreConnection.putBlob(handle, keystoreData.toByteArray());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
	
	public KeyStore loadKeystore(ObjectHandle handle, CallbackHandler handler) throws KeystoreNotFoundException, CertificateException, WrongKeystoreCredentialException, MissingKeystoreAlgorithmException, MissingKeystoreProviderException, MissingKeyAlgorithmException, IOException, UnknownContainerException{
		KeystoreData keystoreData = loadKeystoreData(handle);
		return initKeystore(keystoreData, handle.getName(), handler);
	}

	public Tuple<KeyStore, Map<String, String>> loadKeystoreAndAttributes(ObjectHandle handle, CallbackHandler handler) throws KeystoreNotFoundException, CertificateException, WrongKeystoreCredentialException, MissingKeystoreAlgorithmException, MissingKeystoreProviderException, MissingKeyAlgorithmException, IOException, UnknownContainerException{
		KeystoreData keystoreData = loadKeystoreData(handle);
		KeyStore keyStore = initKeystore(keystoreData, handle.getName(), handler);

		return new Tuple<>(keyStore, keystoreData.getAttributesMap());
	}

	/**
	 * Checks if a keystore available for the given handle. This is generally true if
	 * the container exists and the file with name "name" is in that container.
	 * 
	 * @param handle handle to check
	 * @return if a keystore available for the given handle
	 */
	public boolean hasKeystore(ObjectHandle handle) {
		try {
			return extendedStoreConnection.getBlob(handle)!=null;
		} catch (UnknownContainerException | ObjectNotFoundException e) {
			return false;
		}
	}

	
	private KeystoreData loadKeystoreData(ObjectHandle handle) throws KeystoreNotFoundException, UnknownContainerException{
		byte[] keyStoreBytes;
		try {
			keyStoreBytes = extendedStoreConnection.getBlob(handle);
		} catch (ObjectNotFoundException e) {
			throw new KeystoreNotFoundException(e.getMessage(), e);
		}
		
		try {
			return KeystoreData.parseFrom(keyStoreBytes);
		} catch (IOException e) {
			throw new IllegalStateException("Invalid protocol buffer", e);
		}
	}

	private KeyStore initKeystore(KeystoreData keystoreData, String storeid, CallbackHandler handler) throws WrongKeystoreCredentialException, MissingKeystoreAlgorithmException, MissingKeystoreProviderException, MissingKeyAlgorithmException, CertificateException, IOException {
		try {
			return KeyStoreService.loadKeyStore(keystoreData.getKeystore().toByteArray(), storeid, keystoreData.getType(), handler);
		} catch (UnrecoverableKeyException e) {
			throw new WrongKeystoreCredentialException(e);
		} catch (KeyStoreException e) {
			if(e.getCause()!=null){
				Throwable cause = e.getCause();
				if(cause instanceof NoSuchAlgorithmException){
					throw new MissingKeystoreAlgorithmException(cause.getMessage(), cause);
				}
				if(cause instanceof NoSuchProviderException){
					throw new MissingKeystoreProviderException(cause.getMessage(), cause);
				}
			}
			throw new IllegalStateException("Unidentified keystore exception", e);
		} catch (NoSuchAlgorithmException e) {
			throw new MissingKeyAlgorithmException(e.getMessage(), e);
		}
	}
	
	public void saveKeyStore(KeyStore keystore, CallbackHandler storePassHandler, KeyStoreLocation keyStoreLocation) {
		try {
			// Match store type aggainst file extension
			if(!keyStoreLocation.getKeyStoreType().equals(new KeyStoreType(keystore.getType())))
					throw new ExtendedPersistenceException("Invalid store type - expected : " + keystore.getType() + " but is: " + keyStoreLocation.getKeyStoreType().getValue());
			
			// write keystore to byte array.
			LOGGER.debug("write keystore at " + keyStoreLocation + " and with type " + keystore.getType());
			byte[] bs = KeyStoreService.toByteArray(keystore, keyStoreLocation.getLocationHandle().getName(), storePassHandler);
			
			// write byte array to blob store.
			extendedStoreConnection.putBlob(keyStoreLocation.getLocationHandle() , bs);
		} catch (Exception e) {
			BaseExceptionHandler.handle(e);
		}
	}
	
	public KeyStore loadKeystore(KeyStoreLocation keyStoreLocation, CallbackHandler handler) {
		try {
			// Read bytes
			byte[] ksBytes = extendedStoreConnection.getBlob(keyStoreLocation.getLocationHandle());
			LOGGER.debug("loaded keystore has size:" + ksBytes.length);
			// Initialize key store
			return KeyStoreService.loadKeyStore(ksBytes, keyStoreLocation.getLocationHandle().getName(), keyStoreLocation.getKeyStoreType().getValue(), handler);
		} catch (Exception e) {
			throw BaseExceptionHandler.handle(e);
		}
	}	
	
}