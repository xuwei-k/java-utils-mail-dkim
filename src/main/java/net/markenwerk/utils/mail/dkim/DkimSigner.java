/*
 * Copyright (C) 2015 Torsten Krause.
 * 
 * This file is part of 'A DKIM library for JavaMail', hereafter
 * called 'this library', identified by the following coordinates:
 * 
 *    groupID: net.markenwerk
 *    artifactId: utils-mail-dkim
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 * 
 * See the LICENSE and NOTICE files in the root directory for further
 * information.
 * 
 * This file incorporates work covered by the following copyright and  
 * permission notice:
 *  
 *    Copyright 2008 The Apache Software Foundation or its licensors, as
 *    applicable.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    A licence was granted to the ASF by Florian Sager on 30 November 2008
 */
package net.markenwerk.utils.mail.dkim;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.mail.Header;
import javax.mail.MessagingException;

import com.sun.mail.util.CRLFOutputStream;

/**
 * Main class providing a signature according to DKIM RFC 4871.
 * 
 * @author Torsten Krause (tk at markenwerk dot net)
 * @author Florian Sager
 * @since 1.0.0
 */
public class DkimSigner {

	private static final String DKIM_SIGNATUR_HEADER = "DKIM-Signature";
	private static final int MAX_HEADER_LENGTH = 67;

	private static final List<String> MIMIMUM_HEADERS_TO_SIGN = new ArrayList<String>(3);
	private static final List<String> DEFAULT_HEADERS_TO_SIGN = new ArrayList<String>(28);

	static {
		MIMIMUM_HEADERS_TO_SIGN.add("From");
		MIMIMUM_HEADERS_TO_SIGN.add("To");
		MIMIMUM_HEADERS_TO_SIGN.add("Subject");

		DEFAULT_HEADERS_TO_SIGN.addAll(MIMIMUM_HEADERS_TO_SIGN);
		DEFAULT_HEADERS_TO_SIGN.add("Content-Description");
		DEFAULT_HEADERS_TO_SIGN.add("Content-ID");
		DEFAULT_HEADERS_TO_SIGN.add("Content-Type");
		DEFAULT_HEADERS_TO_SIGN.add("Content-Transfer-Encoding");
		DEFAULT_HEADERS_TO_SIGN.add("Cc");
		DEFAULT_HEADERS_TO_SIGN.add("Date");
		DEFAULT_HEADERS_TO_SIGN.add("In-Reply-To");
		DEFAULT_HEADERS_TO_SIGN.add("List-Subscribe");
		DEFAULT_HEADERS_TO_SIGN.add("List-Post");
		DEFAULT_HEADERS_TO_SIGN.add("List-Owner");
		DEFAULT_HEADERS_TO_SIGN.add("List-Id");
		DEFAULT_HEADERS_TO_SIGN.add("List-Archive");
		DEFAULT_HEADERS_TO_SIGN.add("List-Help");
		DEFAULT_HEADERS_TO_SIGN.add("List-Unsubscribe");
		DEFAULT_HEADERS_TO_SIGN.add("MIME-Version");
		DEFAULT_HEADERS_TO_SIGN.add("Message-ID");
		DEFAULT_HEADERS_TO_SIGN.add("Resent-Sender");
		DEFAULT_HEADERS_TO_SIGN.add("Resent-Cc");
		DEFAULT_HEADERS_TO_SIGN.add("Resent-Date");
		DEFAULT_HEADERS_TO_SIGN.add("Resent-To");
		DEFAULT_HEADERS_TO_SIGN.add("Reply-To");
		DEFAULT_HEADERS_TO_SIGN.add("References");
		DEFAULT_HEADERS_TO_SIGN.add("Resent-Message-ID");
		DEFAULT_HEADERS_TO_SIGN.add("Resent-From");
		DEFAULT_HEADERS_TO_SIGN.add("Sender");
	}

	private final Set<String> headersToSign = new HashSet<String>(DEFAULT_HEADERS_TO_SIGN);

	private SigningAlgorithm signingAlgorithm = SigningAlgorithm.SHA256_WITH_RSA;
	private Signature signatureService;
	private MessageDigest messageDigest;
	private String signingDomain;
	private String selector;
	private String identity;
	private boolean lengthParam;
	private boolean zParam;
	private Canonicalization headerCanonicalization = Canonicalization.RELAXED;
	private Canonicalization bodyCanonicalization = Canonicalization.SIMPLE;
	private PrivateKey privatekey;

	public DkimSigner(String signingDomain, String selector, PrivateKey privkey) throws Exception {
		initDkimSigner(signingDomain, selector, privkey);
	}

	public DkimSigner(String signingDomain, String selector, String privateKeyFilename) throws Exception {

		// read private key DER file
		File privateKeyFile = new File(privateKeyFilename);
		DataInputStream in = new DataInputStream(new FileInputStream(privateKeyFile));
		byte[] privKeyBytes = new byte[(int) privateKeyFile.length()];
		in.read(privKeyBytes);
		in.close();

		// decode private key
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privKeyBytes);
		RSAPrivateKey privKey = (RSAPrivateKey) keyFactory.generatePrivate(privSpec);
		initDkimSigner(signingDomain, selector, privKey);

	}

	private void initDkimSigner(String signingDomain, String selector, PrivateKey privkey) throws DkimException {

		if (!DkimUtil.isValidDomain(signingDomain)) {
			throw new DkimException(signingDomain + " is an invalid signing domain");
		}

		this.signingDomain = signingDomain;
		this.selector = selector.trim();
		this.privatekey = privkey;
		this.setSigningAlgorithm(this.signingAlgorithm);
	}

	public String getIdentity() {
		return identity;
	}

	public void setIdentity(String identity) throws DkimException {
		if (null != identity) {
			identity = identity.trim();
			if (!(identity.endsWith("@" + signingDomain) || identity.endsWith("." + signingDomain))) {
				throw new DkimException("The domain part of " + identity + " has to be " + signingDomain
						+ " or a subdomain thereof");
			}
		}
		this.identity = identity;
	}

	public Canonicalization getBodyCanonicalization() {
		return bodyCanonicalization;
	}

	public void setBodyCanonicalization(Canonicalization bodyCanonicalization) {
		this.bodyCanonicalization = bodyCanonicalization;
	}

	public Canonicalization getHeaderCanonicalization() {
		return headerCanonicalization;
	}

	public void setHeaderCanonicalization(Canonicalization headerCanonicalization) {
		this.headerCanonicalization = headerCanonicalization;
	}

	public void addHeaderToSign(String header) {
		if (null != header && 0 != header.length()) {
			headersToSign.add(header);
		}
	}

	public void removeHeaderToSign(String header) {
		if (null != header && 0 != header.length() && !MIMIMUM_HEADERS_TO_SIGN.contains(header)) {
			headersToSign.remove(header);
		}
	}

	public void setLengthParam(boolean lengthParam) {
		this.lengthParam = lengthParam;
	}

	public boolean getLengthParam() {
		return lengthParam;
	}

	public boolean isZParam() {
		return zParam;
	}

	public void setZParam(boolean param) {
		zParam = param;
	}

	public SigningAlgorithm getSigningAlgorithm() {
		return signingAlgorithm;
	}

	public void setSigningAlgorithm(SigningAlgorithm signingAlgorithm) throws DkimException {

		try {
			this.messageDigest = MessageDigest.getInstance(signingAlgorithm.getHashNotation());
		} catch (NoSuchAlgorithmException nsae) {
			throw new DkimException("The hashing algorithm " + signingAlgorithm.getHashNotation()
					+ " is not known by the JVM", nsae);
		}

		try {
			this.signatureService = Signature.getInstance(signingAlgorithm.getJavaNotation());
		} catch (NoSuchAlgorithmException nsae) {
			throw new DkimException("The signing algorithm " + signingAlgorithm.getJavaNotation()
					+ " is not known by the JVM", nsae);
		}

		try {
			this.signatureService.initSign(privatekey);
		} catch (InvalidKeyException ike) {
			throw new DkimException("The provided private key is invalid", ike);
		}

		this.signingAlgorithm = signingAlgorithm;
	}

	public String sign(DkimMessage message) throws DkimException, MessagingException {

		Map<String, String> dkimSignature = new LinkedHashMap<String, String>();
		dkimSignature.put("v", "1");
		dkimSignature.put("a", this.signingAlgorithm.getRfc4871Notation());
		dkimSignature.put("q", "dns/txt");
		dkimSignature.put("c", getHeaderCanonicalization().getType() + "/" + getBodyCanonicalization().getType());
		dkimSignature.put("t", ((long) new Date().getTime() / 1000) + "");
		dkimSignature.put("s", this.selector);
		dkimSignature.put("d", this.signingDomain);

		// set identity inside signature
		if (identity != null) {
			dkimSignature.put("i", DkimUtil.QuotedPrintable(identity));
		}

		// process header
		List<String> assureHeaders = new ArrayList<String>(MIMIMUM_HEADERS_TO_SIGN);

		// intersect defaultHeadersToSign with available headers
		StringBuffer headerList = new StringBuffer();
		StringBuffer headerContent = new StringBuffer();
		StringBuffer zParamString = new StringBuffer();

		@SuppressWarnings("unchecked")
		Enumeration<Header> headerLines = message.getAllHeaders();
		while (headerLines.hasMoreElements()) {
			Header header = (Header) headerLines.nextElement();

			String headerName = header.getName();
			if (headersToSign.contains(headerName)) {
				String headerValue = header.getValue();
				headerList.append(headerName).append(":");
				headerContent.append(headerCanonicalization.canonicalizeHeader(headerName, headerValue));
				headerContent.append("\r\n");
				assureHeaders.remove(headerName);
				if (zParam) {
					zParamString.append(headerName);
					zParamString.append(":");
					zParamString.append(DkimUtil.QuotedPrintable(headerValue.trim()).replace("|", "=7C"));
					zParamString.append("|");
				}
			}
		}

		if (!assureHeaders.isEmpty()) {
			throw new DkimException("Could not find the header fields " + DkimUtil.concatArray(assureHeaders, ", ")
					+ " for signing");
		}

		dkimSignature.put("h", headerList.substring(0, headerList.length() - 1));
		if (zParam) {
			String zParamTemp = zParamString.toString();
			dkimSignature.put("z", zParamTemp.substring(0, zParamTemp.length() - 1));
		}

		// process body
		String body = message.getEncodedBody();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		CRLFOutputStream crlfos = new CRLFOutputStream(baos);
		try {
			crlfos.write(body.getBytes());
			crlfos.close();
		} catch (IOException e) {
			throw new DkimException("The body conversion to MIME canonical CRLF line terminator failed", e);
		}
		body = baos.toString();

		try {
			body = bodyCanonicalization.canonicalizeBody(body);
		} catch (IOException e) {
			throw new DkimException("The body canonicalization failed", e);
		}

		if (lengthParam) {
			dkimSignature.put("l", Integer.toString(body.length()));
		}

		// calculate and encode body hash
		dkimSignature.put("bh", DkimUtil.base64Encode(messageDigest.digest(body.getBytes())));

		// create signature
		String serializedSignature = serializeDkimSignature(dkimSignature);

		byte[] signedSignature;
		try {
			headerContent.append(headerCanonicalization.canonicalizeHeader(DKIM_SIGNATUR_HEADER, serializedSignature));
			signatureService.update(headerContent.toString().getBytes());
			signedSignature = signatureService.sign();
		} catch (SignatureException se) {
			throw new DkimException("The signing operation by Java security failed", se);
		}

		return DKIM_SIGNATUR_HEADER + ": " + serializedSignature
				+ foldSignedSignature(DkimUtil.base64Encode(signedSignature), 3);
	}

	private String serializeDkimSignature(Map<String, String> dkimSignature) {

		Set<Entry<String, String>> entries = dkimSignature.entrySet();
		StringBuffer buf = new StringBuffer(), fbuf;
		int pos = 0;

		Iterator<Entry<String, String>> iter = entries.iterator();
		while (iter.hasNext()) {
			Entry<String, String> entry = iter.next();

			// buf.append(entry.getKey()).append("=").append(entry.getValue()).append(";\t");

			fbuf = new StringBuffer();
			fbuf.append(entry.getKey()).append("=").append(entry.getValue()).append(";");

			if (pos + fbuf.length() + 1 > MAX_HEADER_LENGTH) {

				pos = fbuf.length();

				// line folding : this doesn't work "sometimes" --> maybe
				// someone likes to debug this
				/*
				 * int i = 0; while (i<pos) { if
				 * (fbuf.substring(i).length()>MAXHEADERLENGTH) {
				 * buf.append("\r\n\t").append(fbuf.substring(i,
				 * i+MAXHEADERLENGTH)); i += MAXHEADERLENGTH; } else {
				 * buf.append("\r\n\t").append(fbuf.substring(i)); pos -= i;
				 * break; } }
				 */

				buf.append("\r\n\t").append(fbuf);

			} else {
				buf.append(" ").append(fbuf);
				pos += fbuf.length() + 1;
			}
		}

		buf.append("\r\n\tb=");

		return buf.toString().trim();
	}

	private String foldSignedSignature(String s, int offset) {

		int i = 0;
		StringBuffer buf = new StringBuffer();

		while (true) {
			if (offset > 0 && s.substring(i).length() > MAX_HEADER_LENGTH - offset) {
				buf.append(s.substring(i, i + MAX_HEADER_LENGTH - offset));
				i += MAX_HEADER_LENGTH - offset;
				offset = 0;
			} else if (s.substring(i).length() > MAX_HEADER_LENGTH) {
				buf.append("\r\n\t").append(s.substring(i, i + MAX_HEADER_LENGTH));
				i += MAX_HEADER_LENGTH;
			} else {
				buf.append("\r\n\t").append(s.substring(i));
				break;
			}
		}

		return buf.toString();
	}

}
