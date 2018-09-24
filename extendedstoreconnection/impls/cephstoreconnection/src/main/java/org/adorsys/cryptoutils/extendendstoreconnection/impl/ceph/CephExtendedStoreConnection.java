package org.adorsys.cryptoutils.extendendstoreconnection.impl.ceph;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import org.adorsys.cryptoutils.exceptions.BaseException;
import org.adorsys.cryptoutils.exceptions.BaseExceptionHandler;
import org.adorsys.cryptoutils.utils.Frame;
import org.adorsys.cryptoutils.utils.HexUtil;
import org.adorsys.encobject.complextypes.BucketDirectory;
import org.adorsys.encobject.complextypes.BucketPath;
import org.adorsys.encobject.complextypes.BucketPathUtil;
import org.adorsys.encobject.domain.Payload;
import org.adorsys.encobject.domain.PayloadStream;
import org.adorsys.encobject.domain.StorageMetadata;
import org.adorsys.encobject.domain.StorageType;
import org.adorsys.encobject.exceptions.StorageConnectionException;
import org.adorsys.encobject.filesystem.StorageMetadataFlattenerGSON;
import org.adorsys.encobject.service.api.ExtendedStoreConnection;
import org.adorsys.encobject.service.impl.SimplePayloadImpl;
import org.adorsys.encobject.service.impl.SimplePayloadStreamImpl;
import org.adorsys.encobject.service.impl.SimpleStorageMetadataImpl;
import org.adorsys.encobject.types.ListRecursiveFlag;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Created by peter on 17.09.18.
 */
public class CephExtendedStoreConnection implements ExtendedStoreConnection {
    private final static Logger LOGGER = LoggerFactory.getLogger(CephExtendedStoreConnection.class);
    private static final Logger SPECIAL_LOGGER = LoggerFactory.getLogger("SPECIAL_LOGGER");
    private AmazonS3 connection = null;
    private final static String CEPH_TMP_FILE_PREFIX = "CEPH_TMP_FILE_";
    private final static String CEPH_TMP_FILE_SUFFIX = "";
    private static final String STORAGE_METADATA_KEY = "StorageMetadata";
    private StorageMetadataFlattenerGSON gsonHelper = new StorageMetadataFlattenerGSON();

    public CephExtendedStoreConnection(URL url, AmazonS3AccessKey accessKey, AmazonS3SecretKey secretKey) {
        Frame frame = new Frame();
        frame.add("USE CEPH SYSTEM");
        frame.add("(ceph has be up and running )");
        frame.add("url: " + url.toString());
        frame.add("accessKey: " + accessKey.getValue());
        frame.add("secretKey: " + secretKey.getValue());
        LOGGER.info(frame.toString());
        new BaseException("JUST A STACK, TO SEE WHERE THE CONNECTION IS CREATED");


        AWSCredentialsProvider credentialsProvider = new AWSCredentialsProvider() {
            @Override
            public AWSCredentials getCredentials() {
                return new BasicAWSCredentials(accessKey.getValue(), secretKey.getValue());
            }

            @Override
            public void refresh() {

            }
        };

        AwsClientBuilder.EndpointConfiguration endpoint = new AwsClientBuilder.EndpointConfiguration(url.toString(), "US");

        ClientConfiguration clientConfig = new ClientConfiguration();
        // clientConfig.setSocketTimeout(10000);
        clientConfig.setProtocol(Protocol.HTTP);
        clientConfig.disableSocketProxy();
        connection = AmazonS3ClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withEndpointConfiguration(endpoint)
                .withClientConfiguration(clientConfig)
                .withPayloadSigningEnabled(false)
                .enablePathStyleAccess()
                .build();
    }

    @Override
    public void putBlob(BucketPath bucketPath, Payload payload) {
        InputStream inputStream = new ByteArrayInputStream(payload.getData());
        PayloadStream payloadStream = new SimplePayloadStreamImpl(payload.getStorageMetadata(), inputStream);
        putBlobStreamWithMemory(bucketPath, payloadStream, payload.getData().length);
    }

    @Override
    public Payload getBlob(BucketPath bucketPath) {
        return getBlob(bucketPath, null);
    }

    @Override
    public Payload getBlob(BucketPath bucketPath, StorageMetadata storageMetadata) {
        // die hier bereits mitgegebenen StorageMetadata werden dennoch erneut gelesen. Ist im CephInterface so vorgesehen.
        try {
            PayloadStream payloadStream = getBlobStream(bucketPath);
            byte[] content = IOUtils.toByteArray(payloadStream.openStream());
            Payload payload = new SimplePayloadImpl(payloadStream.getStorageMetadata(), content);
            return payload;
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }
    }

    @Override
    public void putBlobStream(BucketPath bucketPath, PayloadStream payloadStream) {
        putBlobStreamWithTempFile(bucketPath, payloadStream);
    }

    @Override
    public PayloadStream getBlobStream(BucketPath bucketPath) {
        // die hier bereits mitgegebenen StorageMetadata werden dennoch erneut gelesen. Ist im CephInterface so vorgesehen.
        return getBlobStream(bucketPath, null);
    }

    @Override
    public PayloadStream getBlobStream(BucketPath bucketPath, StorageMetadata storageMetadata) {
        LOGGER.debug("read for " + bucketPath);
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketPath.getObjectHandle().getContainer(), bucketPath.getObjectHandle().getName());
        S3Object object = connection.getObject(getObjectRequest);
        S3ObjectInputStream objectContent = object.getObjectContent();
        StorageMetadata storageMetadata2 = getStorageMetadataFromObjectdata(object.getObjectMetadata(), bucketPath);
        PayloadStream payloadStream = new SimplePayloadStreamImpl(storageMetadata2, objectContent);
        LOGGER.debug("read ok for " + bucketPath);
        return payloadStream;
    }

    @Override
    public void putBlob(BucketPath bucketPath, byte[] bytes) {
        putBlob(bucketPath, new SimplePayloadImpl(new SimpleStorageMetadataImpl(), bytes));
    }

    @Override
    public StorageMetadata getStorageMetadata(BucketPath bucketPath) {
        SPECIAL_LOGGER.debug("readmetadata " + bucketPath); // Dies LogZeile ist fuer den JUNIT-Tests StorageMetaDataTest
        LOGGER.debug("readmetadata " + bucketPath);
        GetObjectMetadataRequest getObjectMetadataRequest = new GetObjectMetadataRequest(
                bucketPath.getObjectHandle().getContainer(),
                bucketPath.getObjectHandle().getName());
        ObjectMetadata objectMetadata = connection.getObjectMetadata(getObjectMetadataRequest);
        StorageMetadata storageMetadata = getStorageMetadataFromObjectdata(objectMetadata, bucketPath);
        return storageMetadata;
    }

    @Override
    public boolean blobExists(BucketPath bucketPath) {
        // actually using exceptions is not nice, but it seems to be much faster than any list command
        try {
            connection.getObjectMetadata(bucketPath.getObjectHandle().getContainer(), bucketPath.getObjectHandle().getName());
            LOGGER.debug("blob exists " + bucketPath + " TRUE");
            return true;
        } catch (Exception e) {
            LOGGER.debug("blob exists " + bucketPath + " FALSE");
            return false;
        }
    }

    @Override
    public void removeBlob(BucketPath bucketPath) {
        LOGGER.debug("removeBlob " + bucketPath);
        connection.deleteObject(bucketPath.getObjectHandle().getContainer(), bucketPath.getObjectHandle().getName());
    }

    @Override
    public void removeBlobFolder(BucketDirectory bucketDirectory) {
        LOGGER.debug("remove blob folder " + bucketDirectory);
        if (bucketDirectory.getObjectHandle().getName() == null) {
            throw new StorageConnectionException("not a valid bucket directory " + bucketDirectory);
        }
        internalRemoveMultiple(bucketDirectory);
    }

    @Override
    public void createContainer(BucketDirectory bucketDirectory) {
        LOGGER.debug("create bucket " + bucketDirectory);
        if (! containerExists(bucketDirectory)) {
            connection.createBucket(bucketDirectory.getObjectHandle().getContainer());
        }
    }

    @Override
    public boolean containerExists(BucketDirectory bucketDirectory) {
        LOGGER.debug("container exsits " + bucketDirectory);
        return connection.doesBucketExistV2(bucketDirectory.getObjectHandle().getContainer());
    }

    @Override
    public void deleteContainer(BucketDirectory bucketDirectory) {
        LOGGER.debug("delete bucket " + bucketDirectory);
        internalRemoveMultiple(new BucketDirectory(bucketDirectory.getObjectHandle().getContainer()));
    }

    public void deleteContainerORIG(BucketDirectory bucketDirectory) {
        LOGGER.debug("delete bucket " + bucketDirectory);
        if (!containerExists(bucketDirectory)) {
            return;
        }
        List<StorageMetadata> list = list(bucketDirectory, ListRecursiveFlag.TRUE);
        for (StorageMetadata storageMetadata : list) {
            if (storageMetadata.getType().equals(StorageType.BLOB)) {
                removeBlob(new BucketPath(storageMetadata.getName()));
            }
        }
        String fullBucketDirectoryString = BucketPathUtil.getAsString(bucketDirectory);
        String containerString = bucketDirectory.getObjectHandle().getContainer();
        if (fullBucketDirectoryString.equals(containerString)) {
            connection.deleteBucket(bucketDirectory.getObjectHandle().getContainer());
        }
    }

    @Override
    public List<StorageMetadata> list(BucketDirectory bucketDirectory, ListRecursiveFlag listRecursiveFlag) {
        LOGGER.debug("list " + bucketDirectory);
        List<StorageMetadata> returnList = new ArrayList<>();
        if (!containerExists(bucketDirectory)) {
            LOGGER.debug("return empty list for " + bucketDirectory);
            return returnList;
        }

        String container = bucketDirectory.getObjectHandle().getContainer();
        String prefix = bucketDirectory.getObjectHandle().getName();
        if (prefix == null) {
            prefix = BucketPath.BUCKET_SEPARATOR;
        } else {
            prefix = BucketPath.BUCKET_SEPARATOR + prefix;
        }

        if (blobExists(new BucketPath(container, prefix))) {
            // diese If-Abfrage dient dem Spezialfall, dass jemand einen BucketPath als BucketDirectory uebergeben hat.
            // Dann gibt es diesen bereits als file, dann muss eine leere Liste zurücgeben werden
            return new ArrayList<>();
        }

        LOGGER.debug("search in " + container + " with prefix " + prefix + " " + listRecursiveFlag);
        String searchKey = prefix.substring(1); // remove first slash
        ObjectListing ol = connection.listObjects(container, searchKey);
        final List<String> keys = new ArrayList<>();
        ol.getObjectSummaries().forEach(el -> keys.add(BucketPath.BUCKET_SEPARATOR + el.getKey()));
        returnList = filter(container, prefix, keys, listRecursiveFlag);
        if (LOGGER.isTraceEnabled()) {
            returnList.forEach(el -> LOGGER.trace("return for " + bucketDirectory + " :" + el.getName() + " type " + el.getType()));
        }
        return returnList;
    }

    @Override
    public List<BucketDirectory> listAllBuckets() {
        LOGGER.debug("list all buckets");
        List<BucketDirectory> buckets = new ArrayList<>();
        connection.listBuckets().forEach(bucket -> buckets.add(new BucketDirectory(bucket.getName())));
        return buckets;
    }

    public void cleanDatabase() {
        LOGGER.warn("DELETE DATABASE");
        Iterator<Bucket> iterator = connection.listBuckets().iterator();
        while (iterator.hasNext()) {
            deleteContainer(new BucketDirectory(iterator.next().getName()));
        }
    }

    public void showDatabase() {
        try {
            Iterator<Bucket> iterator = connection.listBuckets().iterator();
            while (iterator.hasNext()) {
                String realBucketName = iterator.next().getName();
                ObjectListing ol = connection.listObjects(realBucketName);
                List<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<>();
                for (S3ObjectSummary key : ol.getObjectSummaries()) {
                    LOGGER.debug(realBucketName + " -> " + key.getKey());
                }
            }
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }
    }


    // ==========================================================================

    List<StorageMetadata> filter(String container, String prefix, final List<String> keys, ListRecursiveFlag recursive) {
        List<StorageMetadata> result = new ArrayList<>();
        Set<String> dirs = new HashSet<>();

        int numberOfDelimitersOfPrefix = StringUtils.countMatches(prefix, BucketPath.BUCKET_SEPARATOR);
        if (prefix.length() > BucketPath.BUCKET_SEPARATOR.length()) {
            numberOfDelimitersOfPrefix++;
        }
        int numberOfDelimitersExpected = numberOfDelimitersOfPrefix;

        keys.forEach(key -> {
            if (recursive.equals(ListRecursiveFlag.TRUE)) {
                result.add(getStorageMetadata(new BucketPath(container, key)));
            } else {
                int numberOfDelimitersOfKey = StringUtils.countMatches(key, BucketPath.BUCKET_SEPARATOR);
                if (numberOfDelimitersOfKey == numberOfDelimitersExpected) {
                    result.add(getStorageMetadata(new BucketPath(container, key)));
                }
            }

            if (recursive.equals(ListRecursiveFlag.TRUE)) {
                int lastDelimiter = key.lastIndexOf(BucketPath.BUCKET_SEPARATOR);
                String dir = key.substring(0, lastDelimiter);
                if (dir.length() == 0) {
                    dir = BucketPath.BUCKET_SEPARATOR;
                }
                dirs.add(dir);
            } else {
                int numberOfDelimitersOfKey = StringUtils.countMatches(key, BucketPath.BUCKET_SEPARATOR);
                if (numberOfDelimitersOfKey == numberOfDelimitersExpected + 1) {
                    int lastDelimiter = key.lastIndexOf(BucketPath.BUCKET_SEPARATOR);
                    String dir = key.substring(0, lastDelimiter);
                    dirs.add(dir);
                }
            }

        });
        {
            // Auch wenn kein file gefunden wurde, das Verzeichnis exisitiert und ist daher zurückzugeben
            dirs.add(prefix);
        }

        for (String dir : dirs) {
            SimpleStorageMetadataImpl storageMetadata = new SimpleStorageMetadataImpl();
            storageMetadata.setType(StorageType.FOLDER);
            storageMetadata.setName(BucketPathUtil.getAsString(new BucketDirectory(new BucketPath(container, dir))));
            result.add(storageMetadata);
        }
        return result;
    }

    private void putBlobStreamWithMemory(BucketPath bucketPath, PayloadStream payloadStream, int size) {
        try {
            LOGGER.debug("write stream for " + bucketPath + " with known length " + size);
            SimpleStorageMetadataImpl storageMetadata = new SimpleStorageMetadataImpl(payloadStream.getStorageMetadata());
            storageMetadata.setName(BucketPathUtil.getAsString(bucketPath));
            storageMetadata.setType(StorageType.BLOB);
            ObjectMetadata objectMetadata = geteObjectMetadataFromStorageMetadata(storageMetadata);
            objectMetadata.setContentLength(size);

            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketPath.getObjectHandle().getContainer(), bucketPath.getObjectHandle().getName(), payloadStream.openStream(), objectMetadata);
            PutObjectResult putObjectResult = connection.putObject(putObjectRequest);
            // LOGGER.debug("write of stream for :" + bucketPath + " -> " + putObjectResult.toString());
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }
    }

    private void putBlobStreamWithTempFile(BucketPath bucketPath, PayloadStream payloadStream) {
        try {
            LOGGER.debug("store " + bucketPath + " to tmpfile with unknown size");
            InputStream is = payloadStream.openStream();
            File targetFile = File.createTempFile(CEPH_TMP_FILE_PREFIX, CEPH_TMP_FILE_SUFFIX);
            java.nio.file.Files.copy(
                    is,
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            IOUtils.closeQuietly(is);
            LOGGER.debug(bucketPath + " with tmpfile " + targetFile.getAbsolutePath() + " written with " + targetFile.length() + " bytes -> will now be copied to ceph");
            FileInputStream fis = new FileInputStream(targetFile);

            SimpleStorageMetadataImpl storageMetadata = new SimpleStorageMetadataImpl(payloadStream.getStorageMetadata());
            storageMetadata.setName(BucketPathUtil.getAsString(bucketPath));
            storageMetadata.setType(StorageType.BLOB);
            ObjectMetadata objectMetadata = geteObjectMetadataFromStorageMetadata(storageMetadata);
            objectMetadata.setContentLength(targetFile.length());

            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketPath.getObjectHandle().getContainer(), bucketPath.getObjectHandle().getName(), fis, objectMetadata);
            PutObjectResult putObjectResult = connection.putObject(putObjectRequest);
            IOUtils.closeQuietly(fis);
            LOGGER.debug("stored " + bucketPath + " to ceph with size " + targetFile.length());
            targetFile.delete();
        } catch (Exception e) {
            throw BaseExceptionHandler.handle(e);
        }
    }

    // Ceph speichert die UserMetaData im header des Requests. Dadurch sind sie
    // - caseInsensitive
    // - längenbeschränkt
    // Abgesehen davon gibt es auch Probleme den JsonsString direkt zu übernehmen. Die Excpion beim Put verrät allerdings nicht,
    // was für Probleme das sind. Daher werden die Metadaten in einen lesbaren ByteCode umgewandelt.
    private ObjectMetadata geteObjectMetadataFromStorageMetadata(SimpleStorageMetadataImpl storageMetadata) {
        String metadataAsString = gsonHelper.toJson(storageMetadata);
        String metadataAsHexString = HexUtil.convertBytesToHexString(metadataAsString.getBytes());
        Map<String, String> userMetaData = new HashMap<>();
        userMetaData.put(STORAGE_METADATA_KEY, metadataAsHexString);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setUserMetadata(userMetaData);
        return objectMetadata;
    }

    private StorageMetadata getStorageMetadataFromObjectdata(ObjectMetadata objectMetadata, BucketPath bucketPath) {
        String metadataAsHexString = objectMetadata.getUserMetadata().get(STORAGE_METADATA_KEY);
        if (metadataAsHexString == null) {
            throw new BaseException("UserData do not contain mandatory " + STORAGE_METADATA_KEY + " for " + bucketPath);
/*
            LOGGER.error ("UserData do not contain mandatory " + STORAGE_METADATA_KEY + " for " + bucketPath);
            SimpleStorageMetadataImpl storageMetadata = new SimpleStorageMetadataImpl();
            storageMetadata.setType(StorageType.BLOB);
            storageMetadata.setName(BucketPathUtil.getAsString(bucketPath));
            return storageMetadata;
            */
        }
        String metadataAsString = new String(HexUtil.convertHexStringToBytes(metadataAsHexString));
        return gsonHelper.fromJson(metadataAsString);
    }

    private void internalRemoveMultiple(BucketDirectory bucketDirectory) {
        String container = bucketDirectory.getObjectHandle().getContainer();
        String prefix = bucketDirectory.getObjectHandle().getName();
        if (prefix == null) {
            prefix = "";
        }
        if (! connection.doesBucketExistV2(container)) {
            return;
        }
        ObjectListing ol = connection.listObjects(container, prefix);
        if (ol.getObjectSummaries().isEmpty()) {
            LOGGER.debug("no files found in " + container + " with prefix " + prefix);
        }

        List<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<>();
        for (S3ObjectSummary key : ol.getObjectSummaries()) {
            keys.add(new DeleteObjectsRequest.KeyVersion(key.getKey()));
            if (keys.size() == 100) {
                DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(container);
                deleteObjectsRequest.setKeys(keys);
                LOGGER.debug("DELETE CHUNK CONTENTS OF BUCKET " + container + " with " + keys.size() + " elements");
                DeleteObjectsResult deleteObjectsResult = connection.deleteObjects(deleteObjectsRequest);
                LOGGER.debug("CEPH SERVER CONFIRMED DELETION OF " + deleteObjectsResult.getDeletedObjects().size() + " elements");
                ObjectListing ol2 = connection.listObjects(container);
                LOGGER.debug("CEPH SERVER has remaining " + ol2.getObjectSummaries().size() + " elements");
                if (ol2.getObjectSummaries().size() == ol.getObjectSummaries().size()) {
                    throw new BaseException("Fatal error. Ceph Server confirmied deleltion of " + keys.size() + " elements, but still " + ol.getObjectSummaries().size() + " elementents in " + container);
                }
            }
        }
        if (!keys.isEmpty()) {
            DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(container);
            deleteObjectsRequest.setKeys(keys);
            LOGGER.debug("DELETE CONTENTS OF BUCKET " + container + " with " + keys.size() + " elements");
            DeleteObjectsResult deleteObjectsResult = connection.deleteObjects(deleteObjectsRequest);
            LOGGER.debug("CEPH SERVER CONFIRMED DELETION OF " + deleteObjectsResult.getDeletedObjects().size() + " elements");
        }
        String fullBucketDirectoryString = BucketPathUtil.getAsString(bucketDirectory);
        if (fullBucketDirectoryString.endsWith(BucketPath.BUCKET_SEPARATOR)) {
            fullBucketDirectoryString = fullBucketDirectoryString.substring(0, fullBucketDirectoryString.length()-1);
        }
        String containerString = bucketDirectory.getObjectHandle().getContainer();
        if (fullBucketDirectoryString.equals(containerString)) {
            LOGGER.debug("delete container " + bucketDirectory.getObjectHandle().getContainer());
            connection.deleteBucket(bucketDirectory.getObjectHandle().getContainer());
        }
    }



}
