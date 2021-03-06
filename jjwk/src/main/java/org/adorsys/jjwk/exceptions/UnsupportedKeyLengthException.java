package org.adorsys.jjwk.exceptions;

import org.adorsys.cryptoutils.exceptions.BaseException;

public class UnsupportedKeyLengthException extends BaseException {
	private static final long serialVersionUID = -102550810645375099L;

	public UnsupportedKeyLengthException(String message) {
		super(message);
	}

	public UnsupportedKeyLengthException(String message, Throwable cause) {
		super(message, cause);
	}
	
}
