package org.adorsys.encobject.filesystem;

import org.adorsys.cryptoutils.exceptions.BaseException;
import org.adorsys.cryptoutils.exceptions.NYIException;
import org.adorsys.encobject.domain.BlobMetaInfo;
import org.adorsys.encobject.domain.Payload;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by peter on 08.02.18 at 11:55.
 */
public class FileSystemPayload implements Payload {
    private byte[] document;
    private BlobMetaInfo blobMetaInfo;

    public FileSystemPayload(byte[] document, BlobMetaInfo blobMetaInfo) {
        this.document = document;
        this.blobMetaInfo = blobMetaInfo;
    }

    @Override
    public InputStream openStream() throws IOException {
        throw new NYIException();
    }

    @Override
    public byte[] getData() {
        return document;
    }

    @Override
    public boolean isRepeatable() {
        throw new NYIException();
    }

    @Override
    public void release() {
        throw new NYIException();
    }

    public BlobMetaInfo getBlobMetaInfo() {
        return blobMetaInfo;
    }

    public void setBlobMetaInfo(BlobMetaInfo metaInfo) {
        this.blobMetaInfo = metaInfo;
    }

    @Override
    public void setSensitive(boolean isSensitive) {
        throw new NYIException();
    }

    @Override
    public boolean isSensitive() {
        throw new NYIException();
    }

    @Override
    public void close() throws IOException {
        throw new NYIException();
    }
}
